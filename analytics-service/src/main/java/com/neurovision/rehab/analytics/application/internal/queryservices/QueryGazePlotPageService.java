package com.neurovision.rehab.analytics.application.internal.queryservices;

import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.GazePlotPageQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.GazePlotPageQueryPort.Found;
import com.neurovision.rehab.analytics.application.ports.out.persistence.GazePlotPageQueryPort.NotFound;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotCursor;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotPage;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class QueryGazePlotPageService {
    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAX_PAGE_SIZE = 500;

    private final HistoricalHeatmapReadAuthorizationPort authorization;
    private final GazePlotPageQueryPort pages;
    private final GazePlotCursorCodec cursorCodec;

    @Inject
    public QueryGazePlotPageService(HistoricalHeatmapReadAuthorizationPort authorization,
            GazePlotPageQueryPort pages, GazePlotCursorCodec cursorCodec) {
        this.authorization = Objects.requireNonNull(authorization);
        this.pages = Objects.requireNonNull(pages);
        this.cursorCodec = Objects.requireNonNull(cursorCodec);
    }

    public Uni<Outcome> query(UUID therapySessionId, UUID streamId, int pageSize,
            String cursorToken, String accessToken) {
        if (therapySessionId == null || streamId == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            return outcome(Status.INVALID_REQUEST);
        }
        final AnalyticsProjectionIdentity identity = new AnalyticsProjectionIdentity(
                therapySessionId, streamId, AnalyticsCalculationProfile.BASELINE_V1.calculationVersion());
        final GazePlotCursor cursor;
        try {
            cursor = cursorToken == null ? null : cursorCodec.decode(cursorToken, identity);
        } catch (RuntimeException malformed) {
            return outcome(Status.INVALID_REQUEST);
        }
        if (accessToken == null || accessToken.isBlank()) return outcome(Status.UNAUTHENTICATED);

        final HistoricalHeatmapReadAuthorizationPort.Decision decision;
        try {
            decision = authorization.authorize(therapySessionId, accessToken);
        } catch (RuntimeException unavailable) {
            return outcome(Status.UNAVAILABLE);
        }
        if (decision == HistoricalHeatmapReadAuthorizationPort.Decision.INVALID_TOKEN) {
            return outcome(Status.UNAUTHENTICATED);
        }
        if (decision == HistoricalHeatmapReadAuthorizationPort.Decision.DENIED) {
            return outcome(Status.FORBIDDEN);
        }
        if (decision != HistoricalHeatmapReadAuthorizationPort.Decision.PERMITTED) {
            return outcome(Status.UNAVAILABLE);
        }

        final Uni<GazePlotPageQueryPort.Result> loaded;
        try {
            loaded = pages.loadPage(identity, cursor, pageSize);
        } catch (RuntimeException unavailable) {
            return outcome(Status.UNAVAILABLE);
        }
        if (loaded == null) return outcome(Status.UNAVAILABLE);
        return loaded.map(result -> {
            if (result instanceof Found found) {
                var nextCursor = found.page().nextCursor().map(cursorCodec::encode).orElse(null);
                return new Outcome(Status.OK, found.page(), nextCursor);
            }
            if (result == NotFound.INSTANCE) return new Outcome(Status.NOT_FOUND, null);
            return new Outcome(Status.UNAVAILABLE, null);
        }).onFailure().recoverWithItem(new Outcome(Status.UNAVAILABLE, null));
    }

    private static Uni<Outcome> outcome(Status status) {
        return Uni.createFrom().item(new Outcome(status, null));
    }

    public enum Status { OK, INVALID_REQUEST, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, UNAVAILABLE }

    public record Outcome(Status status, GazePlotPage page, String nextCursorToken) {
        public Outcome(Status status, GazePlotPage page) {
            this(status, page, null);
        }

        public Outcome {
            Objects.requireNonNull(status, "status");
            if ((status == Status.OK) != (page != null)) {
                throw new IllegalArgumentException("Only successful outcomes carry a gaze-plot page");
            }
            if ((status == Status.OK && page.nextCursor().isPresent()) != (nextCursorToken != null)) {
                throw new IllegalArgumentException("Cursor token does not match the gaze-plot page");
            }
        }
    }
}

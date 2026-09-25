package com.neurovision.rehab.analytics.application.internal.queryservices;

import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ReplayPageQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ReplayPageQueryPort.Found;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ReplayPageQueryPort.NotFound;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayLane;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayPage;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayCursorRejectedException;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayProjectionInconsistentException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class QueryReplayPageService {
    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAX_PAGE_SIZE = 500;
    private final HistoricalHeatmapReadAuthorizationPort authorization;
    private final ReplayPageQueryPort pages;
    private final ReplayCursorCodec cursors;

    @Inject
    public QueryReplayPageService(HistoricalHeatmapReadAuthorizationPort authorization,
            ReplayPageQueryPort pages, ReplayCursorCodec cursors) {
        this.authorization = Objects.requireNonNull(authorization);
        this.pages = Objects.requireNonNull(pages);
        this.cursors = Objects.requireNonNull(cursors);
    }

    public Uni<Outcome> query(UUID therapySessionId, UUID streamId, int pageSize,
            String gazeToken, String gameplayToken, String accessToken) {
        if (therapySessionId == null || streamId == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            return outcome(Status.INVALID_REQUEST);
        }
        var identity = new AnalyticsProjectionIdentity(therapySessionId, streamId,
                AnalyticsCalculationProfile.BASELINE_V1.calculationVersion());
        final com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayCursor gaze;
        final com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayCursor gameplay;
        try {
            gaze = gazeToken == null ? null : cursors.decode(gazeToken, identity, ReplayLane.GAZE);
            gameplay = gameplayToken == null ? null : cursors.decode(gameplayToken, identity, ReplayLane.GAMEPLAY);
        } catch (RuntimeException invalid) { return outcome(Status.INVALID_REQUEST); }
        if (accessToken == null || accessToken.isBlank()) return outcome(Status.UNAUTHENTICATED);
        final HistoricalHeatmapReadAuthorizationPort.Decision decision;
        try { decision = authorization.authorize(therapySessionId, accessToken); }
        catch (RuntimeException unavailable) { return outcome(Status.UNAVAILABLE); }
        if (decision == HistoricalHeatmapReadAuthorizationPort.Decision.INVALID_TOKEN) return outcome(Status.UNAUTHENTICATED);
        if (decision == HistoricalHeatmapReadAuthorizationPort.Decision.DENIED) return outcome(Status.FORBIDDEN);
        if (decision != HistoricalHeatmapReadAuthorizationPort.Decision.PERMITTED) return outcome(Status.UNAVAILABLE);
        final Uni<ReplayPageQueryPort.Result> loaded;
        try { loaded = pages.loadPage(identity, gaze, gameplay, pageSize); }
        catch (RuntimeException unavailable) { return outcome(Status.UNAVAILABLE); }
        if (loaded == null) return outcome(Status.UNAVAILABLE);
        return loaded.map(result -> {
            if (result instanceof Found found) {
                String nextGaze = found.page().gaze().nextCursor().map(cursors::encode).orElse(null);
                String nextGameplay = found.page().gameplay().nextCursor().map(cursors::encode).orElse(null);
                return new Outcome(Status.OK, found.page(), nextGaze, nextGameplay);
            }
            return result == NotFound.INSTANCE ? new Outcome(Status.NOT_FOUND, null, null, null)
                    : new Outcome(Status.UNAVAILABLE, null, null, null);
        }).onFailure(ReplayCursorRejectedException.class)
                .recoverWithItem(new Outcome(Status.INVALID_REQUEST, null, null, null))
                .onFailure(ReplayProjectionInconsistentException.class)
                .recoverWithItem(new Outcome(Status.UNAVAILABLE, null, null, null))
                .onFailure().recoverWithItem(new Outcome(Status.UNAVAILABLE, null, null, null));
    }

    private static Uni<Outcome> outcome(Status status) {
        return Uni.createFrom().item(new Outcome(status, null, null, null));
    }

    public enum Status { OK, INVALID_REQUEST, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, UNAVAILABLE }
    public record Outcome(Status status, ReplayPage page, String nextGazeCursor, String nextGameplayCursor) {
        public Outcome {
            Objects.requireNonNull(status);
            if ((status == Status.OK) != (page != null)) throw new IllegalArgumentException("Only successful replay outcomes carry a page");
            if (status != Status.OK && (nextGazeCursor != null || nextGameplayCursor != null)) {
                throw new IllegalArgumentException("Failed replay outcome carries cursors");
            }
        }
    }
}

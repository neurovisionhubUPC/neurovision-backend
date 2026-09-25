package com.neurovision.rehab.analytics.application.internal.queryservices;

import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSummaryQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSummaryQueryPort.Found;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSummaryQueryPort.NotFound;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsSummaryProjection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class QueryAnalyticsSummaryService {
    private final HistoricalHeatmapReadAuthorizationPort authorization;
    private final AnalyticsSummaryQueryPort summaries;

    public QueryAnalyticsSummaryService(HistoricalHeatmapReadAuthorizationPort authorization,
            AnalyticsSummaryQueryPort summaries) {
        this.authorization = Objects.requireNonNull(authorization);
        this.summaries = Objects.requireNonNull(summaries);
    }

    public Uni<Outcome> query(UUID therapySessionId, UUID streamId, String accessToken) {
        if (therapySessionId == null || streamId == null) return outcome(Status.NOT_FOUND);
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

        var identity = new AnalyticsProjectionIdentity(therapySessionId, streamId,
                AnalyticsCalculationProfile.BASELINE_V1.calculationVersion());
        final Uni<AnalyticsSummaryQueryPort.Result> loaded;
        try {
            loaded = summaries.load(identity);
        } catch (RuntimeException unavailable) {
            return outcome(Status.UNAVAILABLE);
        }
        if (loaded == null) return outcome(Status.UNAVAILABLE);
        return loaded.map(result -> {
            if (result instanceof Found found) return new Outcome(Status.OK, found.projection());
            if (result == NotFound.INSTANCE) return new Outcome(Status.NOT_FOUND, null);
            return new Outcome(Status.UNAVAILABLE, null);
        }).onFailure().recoverWithItem(new Outcome(Status.UNAVAILABLE, null));
    }

    private static Uni<Outcome> outcome(Status status) {
        return Uni.createFrom().item(new Outcome(status, null));
    }

    public enum Status { OK, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, UNAVAILABLE }

    public record Outcome(Status status, AnalyticsSummaryProjection projection) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            if ((status == Status.OK) != (projection != null)) {
                throw new IllegalArgumentException("Only successful outcomes carry an analytics summary");
            }
        }
    }
}

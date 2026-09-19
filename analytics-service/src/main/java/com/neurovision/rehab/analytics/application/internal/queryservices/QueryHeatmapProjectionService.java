package com.neurovision.rehab.analytics.application.internal.queryservices;

import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.HeatmapProjectionQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.HeatmapProjectionQueryPort.Found;
import com.neurovision.rehab.analytics.application.ports.out.persistence.HeatmapProjectionQueryPort.NotFound;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class QueryHeatmapProjectionService {
    private final HistoricalHeatmapReadAuthorizationPort authorization;
    private final HeatmapProjectionQueryPort projections;

    public QueryHeatmapProjectionService(HistoricalHeatmapReadAuthorizationPort authorization,
            HeatmapProjectionQueryPort projections) {
        this.authorization = Objects.requireNonNull(authorization);
        this.projections = Objects.requireNonNull(projections);
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
        final Uni<HeatmapProjectionQueryPort.Result> loaded;
        try {
            loaded = projections.load(identity);
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

    public record Outcome(Status status, HeatmapProjection projection) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            if ((status == Status.OK) != (projection != null)) {
                throw new IllegalArgumentException("Only successful outcomes carry a projection");
            }
        }
    }
}

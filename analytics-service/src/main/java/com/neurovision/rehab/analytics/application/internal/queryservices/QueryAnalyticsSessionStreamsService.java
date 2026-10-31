package com.neurovision.rehab.analytics.application.internal.queryservices;

import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSessionStreamsQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSessionStreamsQueryPort.*;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class QueryAnalyticsSessionStreamsService {
    private final HistoricalHeatmapReadAuthorizationPort authorization;
    private final AnalyticsSessionStreamsQueryPort streams;
    public QueryAnalyticsSessionStreamsService(HistoricalHeatmapReadAuthorizationPort authorization,
            AnalyticsSessionStreamsQueryPort streams) {
        this.authorization = Objects.requireNonNull(authorization);
        this.streams = Objects.requireNonNull(streams);
    }
    public Uni<Outcome> query(UUID sessionId, Page page, String originalToken) {
        if (originalToken == null || originalToken.isBlank()) return failure(Status.UNAUTHENTICATED);
        if (sessionId == null || page == null) return failure(Status.INVALID_REQUEST);
        try {
            var decision = authorization.authorize(sessionId, originalToken);
            if (decision == HistoricalHeatmapReadAuthorizationPort.Decision.INVALID_TOKEN) return failure(Status.UNAUTHENTICATED);
            if (decision == HistoricalHeatmapReadAuthorizationPort.Decision.DENIED) return failure(Status.FORBIDDEN);
            if (decision != HistoricalHeatmapReadAuthorizationPort.Decision.PERMITTED) return failure(Status.UNAVAILABLE);
            var result = streams.load(sessionId, page);
            if (result == null) return failure(Status.UNAVAILABLE);
            return result.map(items -> {
                Objects.requireNonNull(items);
                if (items.size() > page.fetchLimit()) throw new IllegalArgumentException("Unbounded discovery result");
                String previous = null;
                for (var item : items) {
                    String id = Objects.requireNonNull(item).streamId().toString();
                    if (previous != null && previous.compareTo(id) >= 0) throw new IllegalArgumentException("Unordered discovery result");
                    previous = id;
                }
                return new Outcome(Status.OK, sessionId, page, items.size() > page.size(),
                        items.subList(0, Math.min(items.size(), page.size())));
            }).onFailure().recoverWithItem(new Outcome(Status.UNAVAILABLE, null, null, false, null));
        } catch (RuntimeException unavailable) {
            return failure(Status.UNAVAILABLE);
        }
    }
    private static Uni<Outcome> failure(Status status) {
        return Uni.createFrom().item(new Outcome(status, null, null, false, null));
    }
    public enum Status { OK, INVALID_REQUEST, UNAUTHENTICATED, FORBIDDEN, UNAVAILABLE }
    public record Outcome(Status status, UUID sessionId, Page page, boolean hasNext, List<Item> items) {
        public Outcome {
            Objects.requireNonNull(status);
            if (status == Status.OK) {
                Objects.requireNonNull(sessionId); Objects.requireNonNull(page); items = List.copyOf(items);
            } else if (sessionId != null || page != null || items != null || hasNext) {
                throw new IllegalArgumentException("Failure cannot expose discovery metadata");
            }
        }
    }
}

package com.neurovision.rehab.analytics.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.HeatmapProjectionQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.HeatmapProjectionQueryPort.Found;
import com.neurovision.rehab.analytics.application.ports.out.persistence.HeatmapProjectionQueryPort.NotFound;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection.CellWeight;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection.CoordinateSpace;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class QueryHeatmapProjectionServiceTest {
    private static final UUID SESSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STREAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final AtomicInteger projectionQueries = new AtomicInteger();
    private HistoricalHeatmapReadAuthorizationPort.Decision decision =
            HistoricalHeatmapReadAuthorizationPort.Decision.PERMITTED;
    private HeatmapProjectionQueryPort.Result result = new Found(projection());
    private RuntimeException projectionFailure;

    @Test
    void authorizesBeforeLoadingTheBaselineProjection() {
        var outcome = query(service());

        assertEquals(QueryHeatmapProjectionService.Status.OK, outcome.status());
        assertSame(((Found) result).projection(), outcome.projection());
        assertEquals("baseline-v1", outcome.projection().identity().calculationVersion());
        assertEquals(1, projectionQueries.get());
    }

    @Test
    void denialAndInvalidTokenNeverQueryMongo() {
        var service = service();
        decision = HistoricalHeatmapReadAuthorizationPort.Decision.DENIED;
        assertEquals(QueryHeatmapProjectionService.Status.FORBIDDEN, query(service).status());
        decision = HistoricalHeatmapReadAuthorizationPort.Decision.INVALID_TOKEN;
        assertEquals(QueryHeatmapProjectionService.Status.UNAUTHENTICATED, query(service).status());
        decision = HistoricalHeatmapReadAuthorizationPort.Decision.UNAVAILABLE;
        assertEquals(QueryHeatmapProjectionService.Status.UNAVAILABLE, query(service).status());
        assertEquals(0, projectionQueries.get());
    }

    @Test
    void mapsMissingProjectionWithoutCollapsingItIntoDependencyFailure() {
        result = NotFound.INSTANCE;

        var outcome = query(service());

        assertEquals(QueryHeatmapProjectionService.Status.NOT_FOUND, outcome.status());
        assertNull(outcome.projection());
        assertEquals(1, projectionQueries.get());
    }

    @Test
    void mapsClinicalMongoAndMalformedProjectionFailuresToUnavailable() {
        var service = service();
        projectionFailure = new HeatmapProjectionQueryPort.MalformedProjectionException(
                "malformed", new IllegalArgumentException("bad document"));
        assertEquals(QueryHeatmapProjectionService.Status.UNAVAILABLE, query(service).status());

        projectionFailure = null;
        decision = HistoricalHeatmapReadAuthorizationPort.Decision.PERMITTED;
        var throwingAuthorization = new QueryHeatmapProjectionService(
                (session, token) -> { throw new IllegalStateException("Clinical unavailable"); },
                identity -> { projectionQueries.incrementAndGet(); return Uni.createFrom().item(result); });
        assertEquals(QueryHeatmapProjectionService.Status.UNAVAILABLE,
                query(throwingAuthorization).status());
        assertEquals(1, projectionQueries.get());
    }

    @Test
    void rejectsMissingBearerBeforeAuthorizationOrMongo() {
        var outcome = service().query(SESSION, STREAM, " ").await().indefinitely();

        assertEquals(QueryHeatmapProjectionService.Status.UNAUTHENTICATED, outcome.status());
        assertEquals(0, projectionQueries.get());
    }

    private QueryHeatmapProjectionService service() {
        HistoricalHeatmapReadAuthorizationPort authorization = (session, token) -> decision;
        HeatmapProjectionQueryPort persistence = identity -> {
            projectionQueries.incrementAndGet();
            if (projectionFailure != null) return Uni.createFrom().failure(projectionFailure);
            return Uni.createFrom().item(result);
        };
        return new QueryHeatmapProjectionService(authorization, persistence);
    }

    private QueryHeatmapProjectionService.Outcome query(QueryHeatmapProjectionService service) {
        return service.query(SESSION, STREAM, "original-jwt").await().indefinitely();
    }

    public static HeatmapProjection projection() {
        var cells = new ArrayList<Long>(List.copyOf(java.util.Collections.nCopies(100, 0L)));
        cells.set(12, 2L);
        return new HeatmapProjection(
                new AnalyticsProjectionIdentity(SESSION, STREAM, "baseline-v1"),
                2, 1, CoordinateSpace.NORMALIZED_ZERO_TO_ONE,
                CellWeight.RAW_GAZE_SAMPLE_COUNT, false, 10, 10, cells,
                0, 2, 2, List.of(new HeatmapProjection.SequenceRange(1, 1)),
                1, true, 2,
                Optional.of(new HeatmapProjection.TimeRange(
                        Instant.parse("2026-09-18T10:00:00Z"),
                        Instant.parse("2026-09-18T10:00:02Z"))));
    }
}

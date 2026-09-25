package com.neurovision.rehab.analytics.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort.Decision;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSummaryQueryPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsSummaryProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class QueryAnalyticsSummaryServiceTest {
    private final UUID sessionId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID streamId = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void deniedClinicalAuthorizationNeverLoadsMongoProjection() {
        var persistenceCalled = new AtomicBoolean();
        var service = new QueryAnalyticsSummaryService((session, token) -> Decision.DENIED, identity -> {
            persistenceCalled.set(true);
            return Uni.createFrom().item(AnalyticsSummaryQueryPort.NotFound.INSTANCE);
        });

        var outcome = service.query(sessionId, streamId, "synthetic-token").await().indefinitely();

        assertEquals(QueryAnalyticsSummaryService.Status.FORBIDDEN, outcome.status());
        assertFalse(persistenceCalled.get());
    }

    @Test
    void permittedClinicalReadQueriesPersistedBaselineProjection() {
        var calls = new java.util.ArrayList<String>();
        var expected = projection();
        var service = new QueryAnalyticsSummaryService((session, token) -> {
            calls.add("clinical");
            assertEquals(sessionId, session);
            assertEquals("synthetic-token", token);
            return Decision.PERMITTED;
        }, identity -> {
            calls.add("mongo");
            assertEquals(new AnalyticsProjectionIdentity(sessionId, streamId, "baseline-v1"), identity);
            return Uni.createFrom().item(new AnalyticsSummaryQueryPort.Found(expected));
        });

        var outcome = service.query(sessionId, streamId, "synthetic-token").await().indefinitely();

        assertEquals(List.of("clinical", "mongo"), calls);
        assertEquals(QueryAnalyticsSummaryService.Status.OK, outcome.status());
        assertTrue(outcome.projection() == expected);
    }

    @Test
    void missingTokenStopsBeforeClinicalAndMongo() {
        var persistenceCalled = new AtomicBoolean();
        var service = new QueryAnalyticsSummaryService((session, token) -> {
            throw new AssertionError("Clinical must not be called without a token");
        }, identity -> {
            persistenceCalled.set(true);
            return Uni.createFrom().item(AnalyticsSummaryQueryPort.NotFound.INSTANCE);
        });

        assertEquals(QueryAnalyticsSummaryService.Status.UNAUTHENTICATED,
                service.query(sessionId, streamId, " ").await().indefinitely().status());
        assertFalse(persistenceCalled.get());
    }

    static AnalyticsSummaryProjection projection() {
        var summary = new BaselineStreamSummary(AnalyticsCalculationProfile.BASELINE_V1, 1, sessionIdStatic(),
                streamIdStatic(), 0, 0, List.of(), 0, true, Optional.empty(),
                new BaselineStreamSummary.SampleCounts(0, 0), BaselineStreamSummary.NumericSummary.empty(),
                List.of(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L),
                new BaselineStreamSummary.Heatmap(10, 10, java.util.Collections.nCopies(100, 0L)),
                new BaselineStreamSummary.Coverage(Optional.empty(), 0, 0),
                new BaselineStreamSummary.PathMetrics(0, BaselineStreamSummary.NumericSummary.empty(), 0),
                new BaselineStreamSummary.GameplayMetrics(BaselineStreamSummary.NumericSummary.empty(),
                        BaselineStreamSummary.OrderedNumericSummary.empty(),
                        BaselineStreamSummary.OrderedNumericSummary.empty(),
                        BaselineStreamSummary.OrderedNumericSummary.empty()));
        var identity = new AnalyticsProjectionIdentity(summary.therapySessionId(), summary.streamId(),
                summary.calculationVersion());
        return new AnalyticsSummaryProjection(identity, 1, summary);
    }

    private static UUID sessionIdStatic() { return UUID.fromString("10000000-0000-0000-0000-000000000001"); }
    private static UUID streamIdStatic() { return UUID.fromString("20000000-0000-0000-0000-000000000001"); }
}

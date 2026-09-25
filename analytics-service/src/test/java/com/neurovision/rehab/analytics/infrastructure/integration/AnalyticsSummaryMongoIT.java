package com.neurovision.rehab.analytics.infrastructure.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSummaryQueryPort.Found;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StreamSummaryProjection;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoAnalyticsSummaryQueryAdapter;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoStreamSummaryProjectionAdapter;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = AnalyticsKafkaMongoTestResource.class, restrictToAnnotatedClass = true)
class AnalyticsSummaryMongoIT {
    private static final UUID SESSION_ID = UUID.fromString("10000000-0000-0000-0000-000000000011");
    private static final UUID STREAM_ID = UUID.fromString("20000000-0000-0000-0000-000000000011");

    @Inject MongoStreamSummaryProjectionAdapter writer;
    @Inject MongoAnalyticsSummaryQueryAdapter reader;

    @Test
    void readsPersistedSyntheticBaselineSummaryBySessionStreamAndCalculation() {
        var summary = emptySummary();
        var identity = new AnalyticsProjectionIdentity(SESSION_ID, STREAM_ID, "baseline-v1");
        writer.storeIfNewer(new StreamSummaryProjection(identity, 2, summary)).await().indefinitely();

        var result = reader.load(identity).await().indefinitely();

        var found = assertInstanceOf(Found.class, result);
        assertEquals(2, found.projection().revision());
        assertEquals(SESSION_ID, found.projection().identity().therapySessionId());
        assertEquals(STREAM_ID, found.projection().identity().streamId());
        assertEquals(0, found.projection().summary().sampleCounts().total());
    }

    private static BaselineStreamSummary emptySummary() {
        return new BaselineStreamSummary(AnalyticsCalculationProfile.BASELINE_V1, 1, SESSION_ID, STREAM_ID,
                0, 0, List.of(), 0, true, Optional.empty(),
                new BaselineStreamSummary.SampleCounts(0, 0), BaselineStreamSummary.NumericSummary.empty(),
                List.of(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L),
                new BaselineStreamSummary.Heatmap(10, 10, Collections.nCopies(100, 0L)),
                new BaselineStreamSummary.Coverage(Optional.empty(), 0, 0),
                new BaselineStreamSummary.PathMetrics(0, BaselineStreamSummary.NumericSummary.empty(), 0),
                new BaselineStreamSummary.GameplayMetrics(BaselineStreamSummary.NumericSummary.empty(),
                        BaselineStreamSummary.OrderedNumericSummary.empty(),
                        BaselineStreamSummary.OrderedNumericSummary.empty(),
                        BaselineStreamSummary.OrderedNumericSummary.empty()));
    }
}

package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.MongoClientSettings;
import com.neurovision.rehab.analytics.application.ports.out.persistence.HeatmapProjectionQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.HeatmapProjectionQueryPort.Found;
import com.neurovision.rehab.analytics.application.ports.out.persistence.HeatmapProjectionQueryPort.MalformedProjectionException;
import com.neurovision.rehab.analytics.application.ports.out.persistence.HeatmapProjectionQueryPort.NotFound;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StreamSummaryProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class MongoHeatmapProjectionQueryAdapterTest {
    private static final UUID SESSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STREAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final AnalyticsProjectionIdentity IDENTITY =
            new AnalyticsProjectionIdentity(SESSION, STREAM, "baseline-v1");

    @Test
    void queriesTheExistingSummaryIdentityAndReconstructsItsPersistedHeatmap() {
        Document stored = storedProjection();

        HeatmapProjectionQueryPort.Result result = MongoHeatmapProjectionQueryAdapter
                .query(IDENTITY, () -> Uni.createFrom().item(stored))
                .await().indefinitely();

        var projection = assertInstanceOf(Found.class, result).projection();
        assertEquals(IDENTITY, projection.identity());
        assertEquals(2, projection.revision());
        assertEquals(1, projection.sourceSchemaVersion());
        assertEquals(10, projection.rows());
        assertEquals(10, projection.columns());
        assertEquals(100, projection.cellCounts().size());
        assertEquals(2, projection.cellCounts().stream().mapToLong(Long::longValue).sum());
        assertEquals(2, projection.gazeSampleCount());
        assertEquals(0, projection.minimumSequence());
        assertEquals(2, projection.maximumSequence());
        assertEquals(2, projection.highSequence());
        assertEquals(1, projection.missingSequenceRanges().size());
        assertEquals(1, projection.lateArrivalCount());
        assertTrue(projection.unknownSessionBoundary());
        assertTrue(projection.observedTimeRange().isPresent());
    }

    @Test
    void modelsMissingSeparatelyFromMalformedPersistence() {
        var missing = MongoHeatmapProjectionQueryAdapter
                .query(IDENTITY, () -> Uni.createFrom().nullItem())
                .await().indefinitely();

        assertEquals(NotFound.INSTANCE, missing);

        Document malformed = storedProjection();
        malformed.get("summary", Document.class).get("heatmap", Document.class)
                .put("cellCounts", List.of(1L));

        assertThrows(MalformedProjectionException.class, () -> MongoHeatmapProjectionQueryAdapter
                .query(IDENTITY, () -> Uni.createFrom().item(malformed))
                .await().indefinitely());
    }

    @Test
    void failsClosedOnUnsupportedOrIdentityMismatchedDocuments() {
        Document unsupported = storedProjection();
        unsupported.put("calculationVersion", "baseline-v2");
        unsupported.get("summary", Document.class).put("profile", "baseline-v2");

        assertThrows(MalformedProjectionException.class, () -> MongoHeatmapProjectionQueryAdapter
                .query(new AnalyticsProjectionIdentity(SESSION, STREAM, "baseline-v2"),
                        () -> Uni.createFrom().item(unsupported))
                .await().indefinitely());

        Document mismatched = storedProjection();
        mismatched.get("summary", Document.class).put("streamId", UUID.randomUUID().toString());

        assertThrows(MalformedProjectionException.class, () -> MongoHeatmapProjectionQueryAdapter
                .query(IDENTITY, () -> Uni.createFrom().item(mismatched))
                .await().indefinitely());
    }

    @Test
    void preservesDatabaseFailuresRatherThanMisclassifyingThemAsMalformedDocuments() {
        var failure = new IllegalStateException("MongoDB unavailable");

        var thrown = assertThrows(IllegalStateException.class, () -> MongoHeatmapProjectionQueryAdapter
                .query(IDENTITY, () -> Uni.createFrom().failure(failure))
                .await().indefinitely());

        assertEquals(failure, thrown);
    }

    @Test
    void buildsTheQueryFromAllThreeProjectionIdentityFields() {
        String filter = MongoHeatmapProjectionQueryAdapter.identityFilter(IDENTITY)
                .toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();

        assertTrue(filter.contains(SESSION.toString()));
        assertTrue(filter.contains(STREAM.toString()));
        assertTrue(filter.contains("baseline-v1"));
    }

    private Document storedProjection() {
        var summary = new BaselineTelemetryMetricsCalculator().calculate(List.of(
                admitted(0, TelemetryAdmissionQuality.INITIAL),
                admitted(2, TelemetryAdmissionQuality.LATE_ARRIVAL)));
        var projection = new StreamSummaryProjection(AnalyticsProjectionIdentity.from(summary), 2, summary);
        return new Document("therapySessionId", SESSION.toString())
                .append("streamId", STREAM.toString())
                .append("calculationVersion", "baseline-v1")
                .append("admittedBatchCount", projection.admittedBatchCount())
                .append("summary", MongoStreamSummaryProjectionAdapter.summaryDocument(summary));
    }

    private AdmittedTelemetryBatch admitted(long sequence, TelemetryAdmissionQuality quality) {
        var time = Instant.parse("2026-09-18T10:00:00Z").plusSeconds(sequence);
        return new AdmittedTelemetryBatch(new AcceptedTelemetryBatch(
                1, UUID.randomUUID(), UUID.randomUUID(), SESSION, STREAM, sequence,
                time, time.plusMillis(1), time.plusMillis(2), UUID.randomUUID(), null,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2 + sequence / 10.0, .3, .9)),
                List.of(new AcceptedTelemetryBatch.GameplaySample(
                        0, .1, .2, .3, .4, sequence + 1, 1.5, .6)),
                "sha256-" + sequence), quality);
    }
}

package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.UpdateResult;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StreamSummaryProjectionPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StreamSummaryProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonString;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class MongoStreamSummaryProjectionAdapterTest {
    private static final UUID SESSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STREAM = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void mapsTheCompleteBaselineSummaryWithoutDeferredClinicalContext() {
        var projection = projection();

        Document summary = MongoStreamSummaryProjectionAdapter.summaryDocument(projection.summary());

        assertEquals("baseline-v1", summary.getString("profile"));
        assertEquals(0L, summary.getLong("minimumSequence"));
        assertEquals(2L, summary.getLong("maximumSequence"));
        assertEquals(2L, summary.getLong("highSequence"));
        assertEquals(1L, summary.getLong("lateArrivalCount"));
        assertEquals(2L, summary.get("sampleCounts", Document.class).getLong("gaze"));
        assertEquals(2L, summary.get("sampleCounts", Document.class).getLong("gameplay"));
        assertEquals(100, summary.get("heatmap", Document.class).getList("cellCounts", Long.class).size());
        assertEquals(1, summary.getList("missingSequenceRanges", Document.class).size());
        assertTrue(summary.containsKey("coverage"));
        assertTrue(summary.containsKey("gazePath"));
        assertTrue(summary.containsKey("gameplay"));
        String serialized = summary.toJson();
        assertTrue(!serialized.contains("patient") && !serialized.contains("doctor")
                && !serialized.contains("exercise") && !serialized.contains("stimulus"));
    }

    @Test
    void storesEmptyOptionalMetricsExplicitlyAsNull() {
        var time = Instant.parse("2026-09-18T10:00:00Z");
        var gazeOnly = new AdmittedTelemetryBatch(new AcceptedTelemetryBatch(
                1, UUID.randomUUID(), UUID.randomUUID(), SESSION, STREAM, 0, time, time, time,
                UUID.randomUUID(), null,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .1, .1, .8)), List.of(), "hash"),
                TelemetryAdmissionQuality.INITIAL);
        var summary = new BaselineTelemetryMetricsCalculator().calculate(List.of(gazeOnly));

        var gameplay = MongoStreamSummaryProjectionAdapter.summaryDocument(summary)
                .get("gameplay", Document.class);

        assertNull(gameplay.get("score", Document.class).get("first"));
        assertNull(gameplay.get("playerTargetDistance", Document.class).get("mean"));
    }

    @Test
    void atomicFilterRequiresTheSameIdentityAndAStrictlyOlderRevision() {
        var projection = projection();
        var registry = MongoClientSettings.getDefaultCodecRegistry();

        var filter = MongoStreamSummaryProjectionAdapter.newerRevisionFilter(projection)
                .toBsonDocument(Document.class, registry).toJson();
        var update = MongoStreamSummaryProjectionAdapter.update(projection)
                .toBsonDocument(Document.class, registry).toJson();

        assertTrue(filter.contains(SESSION.toString()));
        assertTrue(filter.contains(STREAM.toString()));
        assertTrue(filter.contains("baseline-v1"));
        assertTrue(filter.contains("$lt") && filter.contains("2"));
        assertTrue(update.contains("$setOnInsert"));
        assertTrue(update.contains("admittedBatchCount") && update.contains("summary"));
    }

    @Test
    void classifiesInsertAdvanceAndNonAdvancingAtomicResults() {
        assertEquals(StreamSummaryProjectionPort.WriteResult.INSERTED,
                MongoStreamSummaryProjectionAdapter.classify(
                        UpdateResult.acknowledged(0, 0L, new BsonString("new"))));
        assertEquals(StreamSummaryProjectionPort.WriteResult.ADVANCED,
                MongoStreamSummaryProjectionAdapter.classify(UpdateResult.acknowledged(1, 1L, null)));
        assertEquals(StreamSummaryProjectionPort.WriteResult.NOT_ADVANCED,
                MongoStreamSummaryProjectionAdapter.classify(UpdateResult.acknowledged(0, 0L, null)));
        assertThrows(IllegalStateException.class,
                () -> MongoStreamSummaryProjectionAdapter.classify(UpdateResult.unacknowledged()));
    }

    @Test
    void recognizesDuplicateKeyRaceWithoutSwallowingOtherMongoFailures() {
        var duplicate = new MongoWriteException(
                new WriteError(11000, "duplicate projection identity", new BsonDocument()),
                new ServerAddress("localhost", 27017));
        var other = new MongoWriteException(
                new WriteError(42, "other write failure", new BsonDocument()),
                new ServerAddress("localhost", 27017));

        assertTrue(MongoStreamSummaryProjectionAdapter.duplicateKey(
                new IllegalStateException("wrapped", duplicate)));
        assertTrue(!MongoStreamSummaryProjectionAdapter.duplicateKey(other));
    }

    @Test
    void retriesADuplicateInsertRaceOnceWithoutUpsertSoAHigherRevisionCanAdvance() {
        var calls = new AtomicInteger();
        var duplicate = new MongoWriteException(
                new WriteError(11000, "lower revision inserted first", new BsonDocument()),
                new ServerAddress("localhost", 27017));

        var result = MongoStreamSummaryProjectionAdapter.storeAtomically(upsert -> {
            int call = calls.getAndIncrement();
            if (call == 0) {
                assertTrue(upsert);
                return io.smallrye.mutiny.Uni.createFrom().failure(duplicate);
            }
            assertTrue(!upsert);
            return io.smallrye.mutiny.Uni.createFrom().item(UpdateResult.acknowledged(1, 1L, null));
        }).await().indefinitely();

        assertEquals(StreamSummaryProjectionPort.WriteResult.ADVANCED, result);
        assertEquals(2, calls.get());
    }

    @Test
    void duplicateInsertRetryKeepsEqualOrStaleRevisionNonAdvancingAndIsBounded() {
        var calls = new AtomicInteger();
        var duplicate = new MongoWriteException(
                new WriteError(11000, "newer or equal revision inserted first", new BsonDocument()),
                new ServerAddress("localhost", 27017));

        var result = MongoStreamSummaryProjectionAdapter.storeAtomically(upsert -> {
            int call = calls.getAndIncrement();
            if (call == 0) return io.smallrye.mutiny.Uni.createFrom().failure(duplicate);
            assertTrue(!upsert);
            return io.smallrye.mutiny.Uni.createFrom().item(UpdateResult.acknowledged(0, 0L, null));
        }).await().indefinitely();

        assertEquals(StreamSummaryProjectionPort.WriteResult.NOT_ADVANCED, result);
        assertEquals(2, calls.get());
    }

    private StreamSummaryProjection projection() {
        var calculator = new BaselineTelemetryMetricsCalculator();
        var summary = calculator.calculate(List.of(
                admitted(0, TelemetryAdmissionQuality.INITIAL),
                admitted(2, TelemetryAdmissionQuality.LATE_ARRIVAL)));
        return new StreamSummaryProjection(AnalyticsProjectionIdentity.from(summary), 2, summary);
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

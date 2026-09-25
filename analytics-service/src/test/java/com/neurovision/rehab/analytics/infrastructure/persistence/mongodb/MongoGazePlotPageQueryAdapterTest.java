package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.MongoClientSettings;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotCursor;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazeSourceBatch;
import com.neurovision.rehab.analytics.domain.services.GazePlotPageAssembler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class MongoGazePlotPageQueryAdapterTest {
    private static final UUID SESSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STREAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final AnalyticsProjectionIdentity IDENTITY =
            new AnalyticsProjectionIdentity(SESSION, STREAM, "baseline-v1");

    @Test
    void reconstructsVersionedSummaryMetadataAndUsesAdmittedBatchCountAsRevision() {
        var metadata = MongoGazePlotPageQueryAdapter.reconstructMetadata(summary(), IDENTITY);

        assertEquals(7, metadata.projectionRevision());
        assertEquals(1, metadata.sourceSchemaVersion());
        assertEquals(List.of(2L, 5L), List.of(metadata.minimumSequence(), metadata.maximumSequence()));
        assertEquals(4, metadata.gazePointCount());
        assertEquals(1, metadata.missingSequenceRanges().size());
    }

    @Test
    void rejectsMismatchedIdentityUnsupportedVersionAndInconsistentSummaryCounts() {
        var mismatched = summary();
        mismatched.put("streamId", UUID.randomUUID().toString());
        assertThrows(MalformedGazePlotProjectionException.class,
                () -> MongoGazePlotPageQueryAdapter.reconstructMetadata(mismatched, IDENTITY));

        var unsupported = summary();
        ((Document) unsupported.get("summary")).put("profile", "baseline-v2");
        assertThrows(MalformedGazePlotProjectionException.class,
                () -> MongoGazePlotPageQueryAdapter.reconstructMetadata(unsupported, IDENTITY));

        var inconsistent = summary();
        ((Document) ((Document) inconsistent.get("summary")).get("sampleCounts")).put("total", 8L);
        assertThrows(MalformedGazePlotProjectionException.class,
                () -> MongoGazePlotPageQueryAdapter.reconstructMetadata(inconsistent, IDENTITY));
    }

    @Test
    void reconstructsOnlyStrictFiniteAdmittedRawGazeSamples() {
        var source = new Document("status", "ADMITTED").append("schemaVersion", 1)
                .append("therapySessionId", SESSION.toString()).append("streamId", STREAM.toString())
                .append("sequence", 3L).append("capturedAt", "2026-09-24T10:00:00Z")
                .append("gazeSamples", List.of(new Document("offsetMs", 12L)
                        .append("x", .25).append("y", .75).append("confidence", .4)));

        GazeSourceBatch batch = MongoGazePlotPageQueryAdapter.reconstructBatch(source, 1000);
        assertEquals(3, batch.sequence());
        assertEquals(Instant.parse("2026-09-24T10:00:00Z"), batch.capturedAt());
        assertEquals(new GazeSourceBatch.Sample(12, .25, .75, .4), batch.gazeSamples().getFirst());

        source.getList("gazeSamples", Document.class).getFirst().put("x", Double.NaN);
        assertThrows(MalformedGazePlotProjectionException.class,
                () -> MongoGazePlotPageQueryAdapter.reconstructBatch(source, 1000));
        assertThrows(MalformedGazePlotProjectionException.class,
                () -> MongoGazePlotPageQueryAdapter.reconstructBatch(source, 0));
    }

    @Test
    void boundsInboxSequenceQueriesAndUsesStableSequenceSorting() {
        var metadata = MongoGazePlotPageQueryAdapter.reconstructMetadata(summary(), IDENTITY);
        var cursor = new GazePlotCursor(2, IDENTITY, metadata.projectionRevision(), 3, 2, 3, 1,
                Instant.parse("2026-09-24T10:00:00Z"));
        String filter = MongoGazePlotPageQueryAdapter.inboxPageFilter(IDENTITY, metadata, cursor)
                .toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();

        assertTrue(filter.contains("therapySessionId"));
        assertTrue(filter.contains("streamId"));
        assertTrue(filter.contains("ADMITTED"));
        assertTrue(filter.contains("$gte") && filter.contains("3"));
        assertTrue(filter.contains("$lte") && filter.contains("5"));
        assertEquals(64, GazePlotPageAssembler.MAX_BATCH_SCAN);
    }

    private static Document summary() {
        var summary = new Document("profile", "baseline-v1").append("sourceSchemaVersion", 1)
                .append("therapySessionId", SESSION.toString()).append("streamId", STREAM.toString())
                .append("minimumSequence", 2L).append("maximumSequence", 5L).append("highSequence", 5L)
                .append("missingSequenceRanges", List.of(new Document("fromInclusive", 4L).append("toInclusive", 4L)))
                .append("lateArrivalCount", 1L).append("unknownSessionBoundary", true)
                .append("observedTimeRange", new Document("minimum", "2026-09-24T10:00:00Z")
                        .append("maximum", "2026-09-24T10:00:01Z"))
                .append("sampleCounts", new Document("gaze", 4L).append("gameplay", 2L).append("total", 6L));
        return new Document("therapySessionId", SESSION.toString()).append("streamId", STREAM.toString())
                .append("calculationVersion", "baseline-v1").append("admittedBatchCount", 7L)
                .append("summary", summary);
    }
}

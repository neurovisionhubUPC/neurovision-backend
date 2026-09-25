package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.MongoClientSettings;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayCursor;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayLane;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayMetadata;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayCursorRejectedException;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayProjectionInconsistentException;
import com.neurovision.rehab.analytics.domain.services.ReplayPageAssembler;
import java.util.List;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class MongoReplayPageQueryAdapterTest {
    private static final UUID SESSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STREAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final AnalyticsProjectionIdentity IDENTITY = new AnalyticsProjectionIdentity(SESSION, STREAM, "baseline-v1");

    @Test
    void reconstructsProjectionRevisionAndRequestedAdmittedSourceLaneOnly() {
        ReplayMetadata metadata = MongoReplayPageQueryAdapter.metadata(summary(), IDENTITY);
        var gaze = MongoReplayPageQueryAdapter.reconstruct(gazeInboxBatch(), 100, ReplayLane.GAZE);
        var gameplay = MongoReplayPageQueryAdapter.reconstruct(gameplayInboxBatch(), 100, ReplayLane.GAMEPLAY);

        assertEquals(3, metadata.projectionRevision());
        assertEquals(2, metadata.minimumSequence());
        assertEquals(5, metadata.maximumSequence());
        assertEquals(1, metadata.missingSequenceRanges().size());
        assertEquals(UUID.fromString("33333333-3333-3333-3333-333333333333"), gaze.batchId());
        assertEquals(.25, gaze.gazeSamples().getFirst().x());
        assertTrue(gaze.gameplaySamples().isEmpty());
        assertEquals(9L, gameplay.gameplaySamples().getFirst().score());
        assertEquals(11L, gameplay.gameplaySamples().getFirst().offsetMs());
        assertTrue(gameplay.gazeSamples().isEmpty());
    }

    @Test
    void rejectsInvalidSamplesAndBoundsEachLaneToAdmittedLimit() {
        var invalid = gameplayInboxBatch();
        invalid.getList("gameplaySamples", Document.class).getFirst().put("progress", 2.0);
        assertThrows(MalformedGazePlotProjectionException.class,
                () -> MongoReplayPageQueryAdapter.reconstruct(invalid, 100, ReplayLane.GAMEPLAY));
        assertThrows(MalformedGazePlotProjectionException.class,
                () -> MongoReplayPageQueryAdapter.reconstruct(gazeInboxBatch(), 0, ReplayLane.GAZE));
    }

    @Test
    void scopesAndLimitsSequenceOrderedInboxWindowPerLane() {
        var metadata = new ReplayMetadata(IDENTITY, 4, 1, 2, 5, List.of(), true);
        var cursor = new ReplayCursor(1, IDENTITY, ReplayLane.GAZE, 4, 3, -1, 2, 1,
                java.time.Instant.parse("2026-09-24T10:00:00Z"));
        String filter = MongoReplayPageQueryAdapter.inboxFilter(metadata, cursor)
                .toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();

        assertTrue(filter.contains("therapySessionId"));
        assertTrue(filter.contains("streamId"));
        assertTrue(filter.contains("ADMITTED"));
        assertTrue(filter.contains("$gt") && filter.contains("3"));
        assertTrue(filter.contains("$lte") && filter.contains("5"));
        assertEquals(64, ReplayPageAssembler.MAX_BATCH_SCAN);
        assertTrue(MongoReplayPageQueryAdapter.inboxOptions(ReplayLane.GAZE) != null);
    }

    @Test
    void projectsOnlyIdentityAndRequestedLaneAndSupportsLegacyInboxRows() {
        String gazeProjection = MongoReplayPageQueryAdapter.inboxProjection(ReplayLane.GAZE)
                .toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
        String gameplayProjection = MongoReplayPageQueryAdapter.inboxProjection(ReplayLane.GAMEPLAY)
                .toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
        assertTrue(gazeProjection.contains("gazeSamples"));
        assertFalse(gazeProjection.contains("gameplaySamples"));
        assertFalse(gazeProjection.contains("correlationId"));
        assertTrue(gameplayProjection.contains("gameplaySamples"));
        assertFalse(gameplayProjection.contains("gazeSamples"));
        assertFalse(gameplayProjection.contains("traceId"));

        var legacyGaze = gazeInboxBatch();
        legacyGaze.remove("inboxSchemaVersion");
        legacyGaze.remove("correlationId");
        legacyGaze.remove("attemptEventId");
        legacyGaze.remove("gameplaySamples");
        assertEquals(.25, MongoReplayPageQueryAdapter.reconstruct(legacyGaze, 100, ReplayLane.GAZE)
                .gazeSamples().getFirst().x());
    }

    @Test
    void rejectsSummaryCardinalityThatContradictsItsObservedRange() {
        var invalid = summary();
        invalid.put("admittedBatchCount", 4L);
        assertThrows(MalformedGazePlotProjectionException.class,
                () -> MongoReplayPageQueryAdapter.metadata(invalid, IDENTITY));
    }

    @Test
    void mapsLaneGapContradictionToStaleCursorOnlyForCursorBoundRequests() {
        var inconsistency = new ReplayProjectionInconsistentException();

        assertTrue(MongoReplayPageQueryAdapter.normalizeProjectionInconsistency(inconsistency, true)
                instanceof ReplayCursorRejectedException);
        assertEquals(inconsistency, MongoReplayPageQueryAdapter.normalizeProjectionInconsistency(inconsistency, false));
    }

    private static Document summary() {
        var summary = new Document("profile", "baseline-v1").append("sourceSchemaVersion", 1)
                .append("therapySessionId", SESSION.toString()).append("streamId", STREAM.toString())
                .append("minimumSequence", 2L).append("maximumSequence", 5L)
                .append("missingSequenceRanges", List.of(new Document("fromInclusive", 4L).append("toInclusive", 4L)))
                .append("unknownSessionBoundary", true);
        return new Document("therapySessionId", SESSION.toString()).append("streamId", STREAM.toString())
                .append("calculationVersion", "baseline-v1").append("admittedBatchCount", 3L).append("summary", summary);
    }

    private static Document gazeInboxBatch() {
        return new Document("status", "ADMITTED").append("inboxSchemaVersion", 2)
                .append("batchId", "33333333-3333-3333-3333-333333333333")
                .append("attemptEventId", "44444444-4444-4444-4444-444444444444")
                .append("therapySessionId", SESSION.toString()).append("streamId", STREAM.toString())
                .append("sequence", 3L).append("schemaVersion", 1)
                .append("capturedAt", "2026-09-24T10:00:00Z").append("sentAt", "2026-09-24T10:00:01Z")
                .append("serverReceivedAt", "2026-09-24T10:00:02Z")
                .append("correlationId", "55555555-5555-5555-5555-555555555555")
                .append("gazeSamples", List.of(new Document("offsetMs", 10L).append("x", .25).append("y", .75).append("confidence", .4)));
    }

    private static Document gameplayInboxBatch() {
        return gazeInboxBatch().append("gameplaySamples", List.of(new Document("offsetMs", 11L).append("playerX", .1).append("playerY", .2)
                        .append("targetX", .3).append("targetY", .4).append("score", 9L).append("speed", 1.5).append("progress", .6)));
    }
}

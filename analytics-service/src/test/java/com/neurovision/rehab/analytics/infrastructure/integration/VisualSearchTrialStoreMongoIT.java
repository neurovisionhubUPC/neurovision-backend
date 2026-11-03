package com.neurovision.rehab.analytics.infrastructure.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.neurovision.rehab.analytics.application.internal.queryservices.VisualSearchTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultReader;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultStore;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultStore.Stored;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultStore.WriteResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import mutiny.zero.flow.adapters.AdaptersToFlow;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.reactivestreams.Publisher;

/** Four store and four reader cases against real CDI adapters and an owned synthetic Mongo database. */
@QuarkusTest
@TestProfile(VisualSearchTrialStoreMongoProfile.class)
@Timeout(30)
@Execution(ExecutionMode.SAME_THREAD)
class VisualSearchTrialStoreMongoIT {
    @Inject VisualSearchTrialResultStore store;
    @Inject VisualSearchTrialResultReader reader;
    @Inject ReactiveMongoClient client;
    @ConfigProperty(name = "quarkus.mongodb.database") String database;
    @ConfigProperty(name = "analytics.visual-search.results-collection") String collection;
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test void insertsAndReadsAllThreeOutcomesExactly() {
        UUID session = UUID.randomUUID();
        for (var outcome : VisualSearchTrialResult.Outcome.values()) {
            var candidate = candidate(session, outcome.name(), outcome);
            assertTrue(store.find(session, candidate.result().trialId()).await().atMost(WAIT).isEmpty());
            var write = store.insertIfAbsent(candidate).await().atMost(WAIT);
            assertTrue(write.created());
            assertTrue(candidate.equals(write.stored()), "Inserted value differs");
            assertTrue(store.find(session, candidate.result().trialId()).await().atMost(WAIT)
                    .filter(candidate::equals).isPresent(), "Round-trip value differs");
            assertEquals(1L, count(candidate));
        }
        assertEquals(3L, await(rows().countDocuments(new Document("_id.therapySessionId", session.toString()))));
    }

    @Test void identicalReplayReturnsOneUnchangedDocument() {
        var candidate = candidate(UUID.randomUUID(), "opaque/試行 : 1", VisualSearchTrialResult.Outcome.SUCCESS);
        assertTrue(store.insertIfAbsent(candidate).await().atMost(WAIT).created());
        Document before = document(candidate);
        var replay = store.insertIfAbsent(candidate).await().atMost(WAIT);
        assertFalse(replay.created());
        assertTrue(candidate.equals(replay.stored()), "Replay winner differs");
        assertUnchanged(candidate, before);
    }

    @Test void differentResultReturnsExistingWinnerWithoutOverwrite() {
        UUID session = UUID.randomUUID();
        var first = candidate(session, "conflict", VisualSearchTrialResult.Outcome.SUCCESS);
        var second = candidate(session, "conflict", VisualSearchTrialResult.Outcome.TIMEOUT);
        assertTrue(store.insertIfAbsent(first).await().atMost(WAIT).created());
        Document before = document(first);
        var conflict = store.insertIfAbsent(second).await().atMost(WAIT);
        assertFalse(conflict.created());
        assertTrue(first.equals(conflict.stored()), "Conflict did not return existing winner");
        assertUnchanged(first, before);
    }

    @Test void concurrentEqualAndDifferentCandidatesHaveExactlyOneWinner() throws Exception {
        for (boolean equal : new boolean[]{true, false}) {
            UUID session = UUID.randomUUID();
            var first = candidate(session, "race", VisualSearchTrialResult.Outcome.SUCCESS);
            var second = equal ? first : candidate(session, "race", VisualSearchTrialResult.Outcome.TIMEOUT);
            var start = new CyclicBarrier(2);
            var workers = Executors.newFixedThreadPool(2);
            try {
                Future<WriteResult> left = workers.submit(() -> insertTogether(start, first));
                Future<WriteResult> right = workers.submit(() -> insertTogether(start, second));
                var a = left.get(12, TimeUnit.SECONDS);
                var b = right.get(12, TimeUnit.SECONDS);
                assertTrue(a.created() != b.created(), "Exactly one insert must create the record");
                var winner = a.created() ? a.stored() : b.stored();
                var expected = a.created() ? first : second;
                assertTrue(expected.equals(winner), "Acknowledged winner differs from its candidate");
                assertTrue(winner.equals(a.stored()) && winner.equals(b.stored()), "Race returned different winners");
                assertTrue(store.find(session, "race").await().atMost(WAIT).filter(winner::equals).isPresent());
                assertEquals(1L, count(first));
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS), "Owned workers did not stop");
            }
        }
    }

    @Test void readerFiltersExactSessionAndOriginWithoutChangingEvidence() {
        UUID session = UUID.randomUUID(), otherSession = UUID.randomUUID();
        var fixtures = new ArrayList<Stored>();
        for (var origin : VisualSearchTrialResult.DataOrigin.values()) {
            for (UUID scope : List.of(session, otherSession)) {
                var value = withOrigin(candidate(scope, "origin-" + origin,
                        VisualSearchTrialResult.Outcome.SUCCESS), origin);
                fixtures.add(value);
            }
        }
        seed(fixtures.stream().map(this::fixtureDocument).toList());
        var before = evidenceSnapshot();
        for (var value : fixtures) {
            var actual = reader.read(value.result().therapySessionId(), value.result().dataOrigin(), 1001)
                    .await().atMost(WAIT);
            assertEquals(1, actual.size());
            assertTrue(List.of(value).equals(actual), "Reader returned another session or origin");
        }
        assertTrue(before.equals(evidenceSnapshot()), "Filtering changed stored evidence");
    }

    @Test void readerReturnsAllOutcomesExactlyAndEmptyEvidenceWithoutChangingDocuments() {
        UUID session = UUID.randomUUID();
        var expected = new ArrayList<Stored>();
        for (var outcome : VisualSearchTrialResult.Outcome.values())
            expected.add(candidate(session, "opaque/試行 : " + outcome, outcome));
        seed(expected.stream().map(this::fixtureDocument).toList());
        var before = evidenceSnapshot();
        var actual = reader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT);
        assertEquals(3, actual.size());
        assertTrue(new HashSet<>(expected).equals(new HashSet<>(actual)), "Reader round-trip values differ");
        assertThrows(UnsupportedOperationException.class, () -> actual.add(expected.getFirst()));
        assertTrue(reader.read(session, VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED, 1001)
                .await().atMost(WAIT).isEmpty(), "Empty origin returned evidence");
        assertTrue(reader.read(UUID.randomUUID(), VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001)
                .await().atMost(WAIT).isEmpty(), "Empty session returned evidence");
        assertTrue(before.equals(evidenceSnapshot()), "Reading changed stored evidence");
    }

    @Test void readerAndSummaryEnforceTheThousandTrialCeilingWithoutAPartialSummary() {
        UUID session = UUID.randomUUID();
        var expected = new ArrayList<Stored>();
        for (int i = 0; i < 1000; i++) expected.add(candidate(session, "bounded-" + i,
                VisualSearchTrialResult.Outcome.SUCCESS));
        seed(expected.stream().map(this::fixtureDocument).toList());
        var before = evidenceSnapshot();
        var bounded = reader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 2).await().atMost(WAIT);
        assertEquals(2, bounded.size()); assertEquals(2, new HashSet<>(bounded).size());
        assertTrue(expected.containsAll(bounded), "Small bounded read returned foreign evidence");
        assertEquals(1000, reader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT).size());
        var service = summaryService(session);
        var result = service.query(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, "synthetic-doctor-token")
                .await().atMost(WAIT);
        assertEquals(VisualSearchTrialSummaryService.Status.AVAILABLE, result.status());
        assertEquals(1000, result.summary().reportedTrialCount()); assertEquals(1000, result.summary().hitCount());
        assertEquals(0, result.summary().omissionCount()); assertEquals(0, result.summary().interruptedCount());
        assertEquals(1000, result.summary().eligibleSearchCount());
        assertEquals(0, result.summary().medianSearchDurationMs().orElseThrow().compareTo(java.math.BigDecimal.valueOf(1000)));
        assertTrue(before.equals(evidenceSnapshot()), "Bounded reading changed stored evidence");

        // An explicit fixture write, not a side effect of the reader. Keep the prior observation separate.
        seed(List.of(fixtureDocument(candidate(session, "overflow-sentinel", VisualSearchTrialResult.Outcome.SUCCESS))));
        var overflowBefore = evidenceSnapshot();
        assertEquals(1001, reader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT).size());
        var overflow = service.query(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, "synthetic-doctor-token")
                .await().atMost(WAIT);
        assertEquals(VisualSearchTrialSummaryService.Status.LIMIT_EXCEEDED, overflow.status());
        assertNull(overflow.summary());
        assertTrue(overflowBefore.equals(evidenceSnapshot()), "Overflow reading changed stored evidence");
    }

    @Test void corruptFixtureIsUnavailableNotEmptyOrPartiallyReturnedAndRemainsUnchanged() {
        UUID session = UUID.randomUUID();
        var valid = fixtureDocument(candidate(session, "valid", VisualSearchTrialResult.Outcome.SUCCESS));
        var invalid = fixtureDocument(candidate(session, "invalid", VisualSearchTrialResult.Outcome.SUCCESS));
        invalid.get("result", Document.class).put("confirmedDwellMs", null);
        seed(List.of(valid, invalid));
        var before = evidenceSnapshot();
        var failure = assertThrows(IllegalStateException.class,
                () -> reader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT));
        assertEquals("Visual Search result read unavailable", failure.getMessage()); assertNull(failure.getCause());
        var result = summaryService(session).query(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC,
                "synthetic-doctor-token").await().atMost(WAIT);
        assertEquals(VisualSearchTrialSummaryService.Status.UNAVAILABLE, result.status()); assertNull(result.summary());
        assertTrue(before.equals(evidenceSnapshot()), "Rejected fixture was changed");
    }

    private VisualSearchTrialSummaryService summaryService(UUID session) {
        // The reader and MongoDB are real; DOCTOR permission is deliberately simulated, not a JWT proof.
        var context = candidate(session, "context-only", VisualSearchTrialResult.Outcome.SUCCESS).context();
        return new VisualSearchTrialSummaryService((requested, token) -> {
            assertTrue(session.equals(requested), "Summary requested another session");
            assertEquals("synthetic-doctor-token", token);
            return new VisualSearchTrialSummaryAuthorizationPort.Decision(
                    VisualSearchTrialSummaryAuthorizationPort.Permission.PERMITTED, context);
        }, reader);
    }

    private Stored withOrigin(Stored value, VisualSearchTrialResult.DataOrigin origin) {
        var result = value.result();
        return new Stored(value.context(), new VisualSearchTrialResult(result.schemaVersion(), result.therapySessionId(),
                result.trialId(), result.outcome(), result.searchDurationMs(), result.confirmedDwellMs(), origin));
    }

    private Document fixtureDocument(Stored value) {
        // Explicit synthetic persisted-format fixture, independent of the production encoder/decoder.
        var context = value.context(); var result = value.result();
        return new Document("_id", filter(value).get("_id")).append("storageSchemaVersion", 1)
                .append("context", new Document("configurationId", context.configurationId().toString())
                        .append("configurationVersion", context.configurationVersion())
                        .append("minimumFixationMs", context.minimumFixationMs()).append("maximumSearchTimeMs", context.maximumSearchTimeMs()))
                .append("result", new Document("schemaVersion", result.schemaVersion()).append("outcome", result.outcome().name())
                        .append("searchDurationMs", result.searchDurationMs()).append("confirmedDwellMs", result.confirmedDwellMs())
                        .append("dataOrigin", result.dataOrigin().name()));
    }

    private void seed(List<Document> documents) {
        assertTrue(!documents.isEmpty() && documents.size() <= 1001, "Fixture batch is outside its bound");
        var inserted = await(rows().withWriteConcern(WriteConcern.MAJORITY.withJournal(true)).insertMany(documents));
        assertTrue(inserted.wasAcknowledged(), "Fixture insertion was not acknowledged");
        assertEquals(documents.size(), inserted.getInsertedIds().size());
    }

    private List<String> evidenceSnapshot() {
        // This collection belongs exclusively to this finite test suite, with at most 1021 fixture rows.
        var observed = io.smallrye.mutiny.Multi.createFrom().publisher(AdaptersToFlow.publisher(rows().find().limit(1101)))
                .collect().asList().await().atMost(WAIT);
        assertTrue(observed.size() < 1101, "Fixture evidence bound exceeded");
        return observed.stream().map(Document::toJson).sorted().toList();
    }

    private WriteResult insertTogether(CyclicBarrier start, Stored candidate) throws Exception {
        start.await(2, TimeUnit.SECONDS);
        return store.insertIfAbsent(candidate).await().atMost(WAIT);
    }

    private Stored candidate(UUID session, String trial, VisualSearchTrialResult.Outcome outcome) {
        return new Stored(new VisualSearchTrialContext(session, new UUID(0, 2), 3, 500, 3000),
                new VisualSearchTrialResult(1, session, trial, outcome,
                        outcome == VisualSearchTrialResult.Outcome.TIMEOUT ? 3000 : 1000,
                        outcome == VisualSearchTrialResult.Outcome.SUCCESS ? 500L : null,
                        VisualSearchTrialResult.DataOrigin.SYNTHETIC));
    }

    private MongoCollection<Document> rows() {
        // The existing resource generates this database; direct system-property overrides are refused.
        assertTrue(database.startsWith("analytics_streams_it_"), "Database is not resource-owned");
        assertEquals("visual_search_trial_results_it", collection);
        return client.unwrap().getDatabase(database).getCollection(collection)
                .withReadPreference(ReadPreference.primary()).withReadConcern(ReadConcern.MAJORITY)
                .withTimeout(5000, TimeUnit.MILLISECONDS);
    }

    private Document filter(Stored value) {
        return new Document("_id", new Document("therapySessionId", value.result().therapySessionId().toString())
                .append("trialId", value.result().trialId()));
    }
    private long count(Stored value) { return await(rows().countDocuments(filter(value))); }
    private Document document(Stored value) {
        Document row = await(rows().find(filter(value)).first());
        assertNotNull(row, "Expected stored document is missing");
        return row;
    }
    private void assertUnchanged(Stored value, Document before) {
        assertEquals(1L, count(value));
        assertTrue(before.equals(document(value)), "Stored document changed");
    }
    private <T> T await(Publisher<T> publisher) {
        return Uni.createFrom().publisher(AdaptersToFlow.publisher(publisher)).await().atMost(WAIT);
    }
}

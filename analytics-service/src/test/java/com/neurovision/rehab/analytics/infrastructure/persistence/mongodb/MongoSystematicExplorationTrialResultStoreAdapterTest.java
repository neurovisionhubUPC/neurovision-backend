package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static org.junit.jupiter.api.Assertions.*;
import com.mongodb.*;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SystematicExplorationTrialResultStore.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import io.smallrye.mutiny.Uni;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.reactivestreams.*;

class MongoSystematicExplorationTrialResultStoreAdapterTest {
    private static final UUID SESSION = new UUID(0, 1), CONFIGURATION = new UUID(0, 2);
    private static final Duration WAIT = Duration.ofSeconds(2);
    Stored stored(String trial, SystematicExplorationTrialResult.Outcome outcome) {
        var visits = outcome == SystematicExplorationTrialResult.Outcome.COMPLETED
                ? Arrays.stream(SystematicExplorationTrialResult.Quadrant.values())
                    .map(q -> new SystematicExplorationTrialResult.Visit(q, 1000, 500)).toList()
                : List.of(new SystematicExplorationTrialResult.Visit(
                    SystematicExplorationTrialResult.Quadrant.TOP_LEFT, 1000, 500));
        return new Stored(new SystematicExplorationTrialContext(SESSION, CONFIGURATION, 3, 500, 3000),
                new SystematicExplorationTrialResult(SESSION, trial, VisualSearchTrialResult.DataOrigin.SYNTHETIC, outcome,
                        outcome == SystematicExplorationTrialResult.Outcome.COMPLETED ? 4000
                            : outcome == SystematicExplorationTrialResult.Outcome.TIMED_OUT ? 4000 : 1500, visits));
    }
    MongoSystematicExplorationTrialResultStoreAdapter adapter(Fake fake) {
        return new MongoSystematicExplorationTrialResultStoreAdapter(fake, "systematic_exploration_trial_results_v1", Duration.ofMillis(100));
    }
    final class Fake implements MongoSystematicExplorationTrialResultStoreAdapter.RawOperations {
        int reads, writes; Document key, command, found;
        Uni<Document> acknowledgement = Uni.createFrom().item(new Document("ok", 1.0).append("n", 1));
        public Uni<Document> find(Document identity) { reads++; key = identity; return Uni.createFrom().item(found); }
        public Uni<Document> insert(Document value) { writes++; command = value; return acknowledgement; }
    }

    @Test void deferredInsertUsesOneStableIdentityAndExplicitDurableConcernWithoutDdl() {
        var fake = new Fake(); var candidate = stored("opaque /:$ trial ", SystematicExplorationTrialResult.Outcome.COMPLETED);
        var uni = adapter(fake).insertIfAbsent(candidate);
        assertEquals(0, fake.writes);
        var result = uni.await().atMost(WAIT);
        assertTrue(result.created()); assertEquals(candidate, result.stored());
        assertEquals(1, fake.writes); assertEquals(0, fake.reads);
        assertEquals(Set.of("insert", "documents", "ordered", "writeConcern"), fake.command.keySet());
        assertEquals("systematic_exploration_trial_results_v1", fake.command.getString("insert")); assertEquals(true, fake.command.getBoolean("ordered"));
        var concern = fake.command.get("writeConcern", Document.class);
        assertEquals("majority", concern.getString("w")); assertEquals(true, concern.getBoolean("j"));
        var rows = fake.command.getList("documents", Document.class); assertEquals(1, rows.size());
        assertEquals(new Document("therapySessionId", SESSION.toString()).append("trialId", candidate.result().trialId()), rows.getFirst().get("_id"));
        assertEquals(List.of("therapySessionId", "trialId"), new ArrayList<>(rows.getFirst().get("_id", Document.class).keySet()));
    }

    @Test void findRoundTripsAllOutcomesAndPreservesOpaqueIdentityWithoutCoercions() {
        for (var outcome : SystematicExplorationTrialResult.Outcome.values()) {
            var fake = new Fake(); var candidate = stored(" a|b/\u0000Ã© ", outcome);
            fake.found = MongoSystematicExplorationTrialResultStoreAdapter.document(candidate);
            var result = adapter(fake).find(SESSION, candidate.result().trialId()).await().atMost(WAIT);
            assertEquals(Optional.of(candidate), result); assertEquals(1, fake.reads); assertEquals(0, fake.writes);
            assertEquals(candidate.result().trialId(), fake.key.getString("trialId"));
            assertEquals("StoredSystematicExplorationTrial[redacted]", result.orElseThrow().toString());
        }
        var fake = new Fake(); assertEquals(Optional.empty(), adapter(fake).find(SESSION, "missing").await().atMost(WAIT));
        assertEquals(1, fake.reads); assertEquals(0, fake.writes);
    }

    @Test void duplicateReadsExactlyOneWinnerWithoutOverwritingOrSecondInsert() {
        for (var outcome : SystematicExplorationTrialResult.Outcome.values()) {
            var fake = new Fake(); var candidate = stored("same", SystematicExplorationTrialResult.Outcome.COMPLETED);
            var winner = stored("same", outcome);
            fake.found = MongoSystematicExplorationTrialResultStoreAdapter.document(winner);
            var original = snapshot(fake.found);
            fake.acknowledgement = Uni.createFrom().item(duplicate());
            var result = adapter(fake).insertIfAbsent(candidate).await().atMost(WAIT);
            assertFalse(result.created()); assertEquals(winner, result.stored());
            assertEquals(1, fake.writes); assertEquals(1, fake.reads);
            assertTrue(original.equals(snapshot(fake.found)), "Stored fixture changed");
        }
    }

    @Test void missingOrCorruptDuplicateWinnerFailsRatherThanClaimingCreatedOrAbsent() {
        for (Document found : Arrays.asList(null, new Document(),
                MongoSystematicExplorationTrialResultStoreAdapter.document(stored("another", SystematicExplorationTrialResult.Outcome.COMPLETED)))) {
            var fake = new Fake(); fake.found = found; fake.acknowledgement = Uni.createFrom().item(duplicate());
            safeFailure(() -> adapter(fake).insertIfAbsent(stored("same", SystematicExplorationTrialResult.Outcome.COMPLETED)).await().atMost(WAIT));
            assertEquals(1, fake.writes); assertEquals(1, fake.reads);
        }
    }

    @Test void malformedAcknowledgementsAndWriteConcernErrorsNeverTriggerRetryOrWinnerRead() {
        var bad = new ArrayList<Document>();
        bad.add(null); bad.add(new Document()); bad.add(new Document("ok", 0).append("n", 1));
        bad.add(new Document("ok", 1).append("n", 0)); bad.add(new Document("ok", 1).append("n", 2));
        bad.add(new Document("ok", 1).append("n", 1.0));
        bad.add(new Document("ok", 1).append("n", 1).append("writeConcernError", new Document("code", 64)));
        bad.add(new Document("ok", 1).append("n", 1).append("writeErrors", List.of(new Document("code", 11000).append("index", 0))));
        bad.add(new Document("ok", 1).append("n", 0).append("writeErrors", List.of(new Document("code", 121).append("index", 0))));
        bad.add(new Document("ok", 1).append("n", 0).append("writeErrors", List.of(new Document("code", 11000).append("index", 1))));
        for (Document reply : bad) {
            var fake = new Fake(); fake.acknowledgement = Uni.createFrom().item(reply);
            safeFailure(() -> adapter(fake).insertIfAbsent(stored("trial", SystematicExplorationTrialResult.Outcome.COMPLETED)).await().atMost(WAIT));
            assertEquals(1, fake.writes); assertEquals(0, fake.reads);
        }
    }

    @Test void corruptDocumentsFailClosedInsteadOfReturningEmptyOrChangingState() {
        for (int kind = 0; kind < 8; kind++) {
            var fake = new Fake(); var doc = MongoSystematicExplorationTrialResultStoreAdapter.document(stored("trial", SystematicExplorationTrialResult.Outcome.COMPLETED));
            switch (kind) {
                case 0 -> doc.put("storageSchemaVersion", 2);
                case 1 -> doc.get("_id", Document.class).put("therapySessionId", new UUID(0, 99).toString());
                case 2 -> doc.get("context", Document.class).put("configurationVersion", 3.0);
                case 3 -> doc.get("result", Document.class).put("outcome", "UNKNOWN_PRIVATE_VALUE");
                case 4 -> doc.get("result", Document.class).getList("visits", Document.class).getFirst().put("confirmedDwellMs", null);
                case 5 -> doc.get("context", Document.class).put("minimumFixationMs", 2000L);
                case 6 -> doc.put("extra", "private");
                default -> doc.get("context", Document.class).put("configurationId", "1-1-1-1-1");
            }
            fake.found = doc; var original = snapshot(doc);
            safeFailure(() -> adapter(fake).find(SESSION, "trial").await().atMost(WAIT));
            assertTrue(original.equals(snapshot(doc)), "Rejected fixture changed"); assertEquals(0, fake.writes);
        }
    }

    @Test void timeoutAndTransportFailureDoNotRetryAndInvalidArgumentsDoNotDispatch() {
        for (boolean timeout : List.of(false, true)) {
            var fake = new Fake(); fake.acknowledgement = timeout ? Uni.createFrom().nothing()
                    : Uni.createFrom().failure(new IllegalStateException("private-upstream"));
            safeFailure(() -> adapter(fake).insertIfAbsent(stored("trial", SystematicExplorationTrialResult.Outcome.COMPLETED)).await().atMost(WAIT));
            assertEquals(1, fake.writes); assertEquals(0, fake.reads);
        }
        var fake = new Fake();
        safeFailure(() -> adapter(fake).find(null, "trial").await().atMost(WAIT));
        safeFailure(() -> adapter(fake).find(SESSION, " ").await().atMost(WAIT));
        safeFailure(() -> adapter(fake).insertIfAbsent(null).await().atMost(WAIT));
        assertEquals(0, fake.writes); assertEquals(0, fake.reads);
    }

    @Test void productionDriverViewsUsePrimaryMajorityTimeoutAndRawInsertCommand() {
        var calls = new ArrayList<String>(); final Document[] command = {null};
        @SuppressWarnings("unchecked") var find = proxy(com.mongodb.reactivestreams.client.FindPublisher.class, (o,m,a) -> {
            assertEquals("first", m.getName()); calls.add("first"); return publisher(null);
        });
        @SuppressWarnings("unchecked") var collection = proxy(com.mongodb.reactivestreams.client.MongoCollection.class, (o,m,a) -> {
            switch (m.getName()) {
                case "withReadPreference" -> assertEquals(ReadPreference.primary(), a[0]);
                case "withReadConcern" -> assertEquals(ReadConcern.MAJORITY, a[0]);
                case "withTimeout" -> { assertEquals(100L, a[0]); calls.add("read_timeout"); }
                case "find" -> { calls.add("find"); return find; }
                default -> throw new AssertionError("Unexpected collection operation");
            }
            return o;
        });
        var db = proxy(com.mongodb.reactivestreams.client.MongoDatabase.class, (o,m,a) -> {
            if (m.getName().equals("getCollection")) { assertEquals("results", a[0]); return collection; }
            if (m.getName().equals("withTimeout")) { assertEquals(100L, a[0]); calls.add("write_timeout"); return o; }
            if (m.getName().equals("runCommand")) {
                assertEquals(ReadPreference.primary(), a[1]); command[0] = (Document) a[0]; calls.add("raw_insert");
                return publisher(new Document("ok", 1).append("n", 1));
            }
            throw new AssertionError("Unexpected database operation");
        });
        var nativeClient = proxy(com.mongodb.reactivestreams.client.MongoClient.class, (o,m,a) -> {
            assertEquals("getDatabase", m.getName()); assertEquals("synthetic", a[0]); return db;
        });
        var client = proxy(io.quarkus.mongodb.reactive.ReactiveMongoClient.class, (o,m,a) -> {
            assertEquals("unwrap", m.getName()); return nativeClient;
        });
        @SuppressWarnings("unchecked") jakarta.enterprise.inject.Instance<io.quarkus.mongodb.reactive.ReactiveMongoClient> clients =
                proxy(jakarta.enterprise.inject.Instance.class, (o,m,a) -> { assertEquals("get", m.getName()); return client; });
        var adapter = new MongoSystematicExplorationTrialResultStoreAdapter(clients, "synthetic", "results", 100);
        assertTrue(calls.isEmpty());
        assertTrue(adapter.find(SESSION, "trial").await().atMost(WAIT).isEmpty());
        assertTrue(adapter.insertIfAbsent(stored("trial", SystematicExplorationTrialResult.Outcome.COMPLETED)).await().atMost(WAIT).created());
        assertEquals(List.of("read_timeout", "find", "first", "write_timeout", "raw_insert"), calls);
        assertEquals("majority", command[0].get("writeConcern", Document.class).getString("w"));
        assertEquals(true, command[0].get("writeConcern", Document.class).getBoolean("j"));
    }

    @Test void storedSchemaKeepsElapsedTimeAndRejectsSiblingDocuments() {
        var candidate = stored("trial", SystematicExplorationTrialResult.Outcome.COMPLETED);
        var row = MongoSystematicExplorationTrialResultStoreAdapter.document(candidate);
        assertEquals(Set.of("configurationId", "configurationVersion", "minimumFixationMs", "maximumStimulusDurationMs"),
                row.get("context", Document.class).keySet());
        assertEquals(Set.of("outcome", "elapsedMs", "visits", "dataOrigin"),
                row.get("result", Document.class).keySet());
        assertEquals(3000L, row.get("context", Document.class).get("maximumStimulusDurationMs"));
        assertEquals(4000L, row.get("result", Document.class).get("elapsedMs"));
        assertEquals(4, row.get("result", Document.class).getList("visits", Document.class).size());
        for (boolean context : List.of(false, true)) {
            var fake = new Fake(); fake.found = MongoSystematicExplorationTrialResultStoreAdapter.document(candidate);
            var section = fake.found.get(context ? "context" : "result", Document.class);
            String field = context ? "maximumStimulusDurationMs" : "elapsedMs";
            section.put(context ? "maximumSearchTimeMs" : "localizationDurationMs", section.remove(field));
            safeFailure(() -> adapter(fake).find(SESSION, "trial").await().atMost(WAIT));
            assertEquals(1, fake.reads); assertEquals(0, fake.writes);
        }
    }

    @Test void invalidVisitsFailClosedWithoutChangingStoredDocuments() {
        for (int kind = 0; kind < 8; kind++) {
            var fake = new Fake();
            fake.found = MongoSystematicExplorationTrialResultStoreAdapter.document(
                    stored("trial", SystematicExplorationTrialResult.Outcome.COMPLETED));
            var result = fake.found.get("result", Document.class);
            var visits = new ArrayList<>(result.getList("visits", Document.class));
            switch (kind) {
                case 0 -> visits.getFirst().put("quadrant", "BOTTOM_RIGHT");
                case 1 -> visits.getFirst().put("timeToVisitMs", 1000.0);
                case 2 -> visits.getFirst().put("confirmedDwellMs", 499L);
                case 3 -> visits.getFirst().put("extra", "private");
                case 4 -> visits.add(new Document(visits.getFirst()));
                case 5 -> visits.removeLast();
                case 6 -> visits.set(0, null);
                default -> result.put("elapsedMs", 4001L);
            }
            result.put("visits", visits); var before = snapshot(fake.found);
            safeFailure(() -> adapter(fake).find(SESSION, "trial").await().atMost(WAIT));
            assertEquals(before, snapshot(fake.found)); assertEquals(0, fake.writes);
        }
    }

    @Test void invalidSettingsFailBeforeDriverUse() {
        for (long milliseconds : new long[]{99, 10001}) {
            assertThrows(IllegalArgumentException.class, () -> new MongoSystematicExplorationTrialResultStoreAdapter(
                    new Fake(), "results", Duration.ofMillis(milliseconds)));
        }
        assertThrows(IllegalArgumentException.class, () -> new MongoSystematicExplorationTrialResultStoreAdapter(
                new Fake(), " ", Duration.ofMillis(100)));
    }

    private Document duplicate() { return new Document("ok", 1).append("n", 0)
            .append("writeErrors", List.of(new Document("index", 0).append("code", 11000).append("errmsg", "private"))); }
    private String snapshot(Document row) {
        return row.toJson(org.bson.json.JsonWriterSettings.builder().outputMode(org.bson.json.JsonMode.EXTENDED).build());
    }
    private void safeFailure(org.junit.jupiter.api.function.Executable operation) {
        var failure = assertThrows(IllegalStateException.class, operation);
        assertEquals("Systematic Exploration result storage unavailable", failure.getMessage()); assertNull(failure.getCause());
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private static <T> Publisher<T> publisher(T item) {
        return subscriber -> subscriber.onSubscribe(new Subscription() {
            boolean done;
            public void request(long n) { if (!done) { done = true; if (item != null) subscriber.onNext(item); subscriber.onComplete(); } }
            public void cancel() { done = true; }
        });
    }
    @Test void servicePreservesUncertainPersistedWriteAndConfirmsAfterClosure() {
        var candidate = stored("same", SystematicExplorationTrialResult.Outcome.COMPLETED);
        Document[] saved = {null}; int[] reads = {0}, writes = {0}; boolean[] mayInsert = {true};
        var operations = new MongoSystematicExplorationTrialResultStoreAdapter.RawOperations() {
            public Uni<Document> find(Document identity) { reads[0]++; return Uni.createFrom().item(saved[0]); }
            public Uni<Document> insert(Document command) {
                writes[0]++; saved[0] = command.getList("documents", Document.class).getFirst();
                return Uni.createFrom().failure(new IllegalStateException("synthetic acknowledgement lost"));
            }
        };
        var store = new MongoSystematicExplorationTrialResultStoreAdapter(operations, "results", Duration.ofMillis(100));
        var service = new com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveSystematicExplorationTrialResultService(
            (session, token) -> new com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialAuthorizationPort.Decision(
                com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialAuthorizationPort.Permission.PERMITTED,
                candidate.context(), mayInsert[0]), store);
        assertEquals(com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveSystematicExplorationTrialResultService.Status.WRITE_OUTCOME_UNKNOWN,
                service.receive(candidate.result(), "synthetic-token").await().atMost(WAIT));
        assertEquals(1, reads[0]); assertEquals(1, writes[0]); var before = snapshot(saved[0]);
        mayInsert[0] = false;
        assertEquals(com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveSystematicExplorationTrialResultService.Status.ALREADY_STORED,
                service.receive(candidate.result(), "synthetic-token").await().atMost(WAIT));
        assertEquals(2, reads[0]); assertEquals(1, writes[0]); assertEquals(before, snapshot(saved[0]));
    }
}

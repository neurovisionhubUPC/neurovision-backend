package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import com.mongodb.WriteConcern;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ConditionalProjectionRebuildWritePort.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.codecs.DocumentCodec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MongoConditionalProjectionRebuildWriteAdapterTest {
    private final UUID session = UUID.randomUUID(), stream = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong();

    @Test void missingUsesOneOrderedInsertAndInheritedAcknowledgedConcern() {
        var fake = new Fake();
        var request = request(false);
        var uni = adapter(fake).writeIfUnchanged(request, Duration.ofSeconds(2));
        assertTrue(fake.calls.isEmpty());
        assertEquals(WriteResult.INSERTED, uni.await().atMost(Duration.ofSeconds(3)));
        assertEquals(List.of("indexes", "write"), fake.calls);
        assertEquals("summaries", fake.command.getString("insert"));
        assertEquals(true, fake.command.getBoolean("ordered"));
        var rows = fake.command.getList("documents", Document.class);
        assertEquals(1, rows.size());
        assertEquals(session.toString(), rows.getFirst().getString("therapySessionId"));
        assertEquals(stream.toString(), rows.getFirst().getString("streamId"));
        assertEquals("baseline-v1", rows.getFirst().getString("calculationVersion"));
        assertEquals(request.candidate().admittedBatchCount(), rows.getFirst().getLong("admittedBatchCount"));
        assertEquals(MongoStreamSummaryProjectionAdapter.summaryDocument(request.candidate().summary()), rows.getFirst().get("summary"));
        assertEquals(WriteConcern.MAJORITY.asDocument(), fake.command.get("writeConcern"));
        assertFalse(fake.command.containsKey("maxTimeMS"));
        assertEquals(1, fake.writes);
    }

    @Test void staleComparesFullExpectedContentAndNeverUpsertsOrSetsOtherFields() {
        var fake = new Fake(); fake.response = updateAck(1, 1);
        var request = request(true);
        assertEquals(WriteResult.REPLACED, run(fake, request));
        assertEquals("summaries", fake.command.getString("update"));
        var operation = fake.command.getList("updates", Document.class).getFirst();
        assertEquals(false, operation.getBoolean("upsert"));
        assertEquals(false, operation.getBoolean("multi"));
        assertEquals(new Document("locale", "simple"), operation.get("collation"));
        var filter = operation.get("q", Document.class);
        assertEquals(session.toString(), filter.getString("therapySessionId"));
        assertEquals(stream.toString(), filter.getString("streamId"));
        assertEquals("baseline-v1", filter.getString("calculationVersion"));
        assertEquals(1L, filter.getLong("admittedBatchCount"));
        assertEquals(MongoStreamSummaryProjectionAdapter.summaryDocument(request.expected().summary()), filter.get("summary"));
        var update = operation.get("u", Document.class);
        assertEquals(Set.of("$set"), update.keySet());
        assertEquals(Set.of("admittedBatchCount", "summary"), update.get("$set", Document.class).keySet());
        assertEquals(2L, update.get("$set", Document.class).getLong("admittedBatchCount"));
    }

    @Test void zeroMatchedAndProvenDuplicateInsertAreConflictsWithoutRetry() {
        var update = new Fake(); update.response = updateAck(0, 0);
        assertEquals(WriteResult.PRECONDITION_FAILED, run(update, request(true)));
        var insert = new Fake();
        insert.response = new Document("ok", 1).append("n", 0).append("writeErrors",
                List.of(new Document("index", 0).append("code", 11000).append("errmsg", "private")));
        assertEquals(WriteResult.PRECONDITION_FAILED, run(insert, request(false)));
        assertEquals(1, update.writes); assertEquals(1, insert.writes);
    }

    @Test void malformedOrAmbiguousAcksNeverClaimSuccessOrKnownNoEffect() {
        for (var response : List.of(new Document(), new Document("ok", 0).append("n", 1),
                new Document("ok", 1).append("n", 1.5), new Document("ok", 1).append("n", 2),
                new Document("ok", 1).append("n", 1).append("writeConcernError", new Document("errmsg", "private")),
                new Document("ok", 1).append("n", 1).append("writeErrors", List.of(new Document("code", 11000))),
                new Document("ok", 1).append("n", 0).append("writeErrors", List.of(new Document("index", 0).append("code", 121))))) {
            var fake = new Fake(); fake.response = response;
            assertSafeFailure(fake, request(false)); assertEquals(1, fake.writes);
        }
        for (var response : List.of(updateAck(1, 0), updateAck(0, 1), updateAck(1, 1).append("upserted", List.of(new Document("_id", "private"))))) {
            var fake = new Fake(); fake.response = response;
            assertSafeFailure(fake, request(true)); assertEquals(1, fake.writes);
        }
    }

    @Test void explicitNullErrorsAndContradictoryDuplicateEvidenceAreUnknown() {
        var fake = new Fake();
        fake.response = new Document("ok", 1).append("n", 1).append("writeErrors", null);
        assertSafeFailure(fake, request(false));
        fake.response = new Document("ok", 1).append("n", 0).append("writeErrors",
                List.of(new Document("index", 0).append("code", 11000)))
                .append("upserted", List.of(new Document("_id", "private")));
        assertSafeFailure(fake, request(false));
    }

    @Test void missingOrUnsafeUniqueIndexPreventsAllMutation() {
        var valid = index();
        var mutations = new ArrayList<Document>();
        for (var pair : Map.of("unique", false, "sparse", true, "partialFilterExpression", new Document(),
                "expireAfterSeconds", 60, "collation", new Document("locale", "en"), "name", "unknown").entrySet()) {
            var changed = new Document(valid); changed.put(pair.getKey(), pair.getValue()); mutations.add(changed);
        }
        mutations.add(new Document(valid).append("key", new Document("streamId", 1).append("therapySessionId", 1).append("calculationVersion", 1)));
        mutations.add(new Document(valid).append("key", new Document("therapySessionId", 1).append("streamId", 1)));
        for (var changed : mutations) {
            var fake = new Fake(); fake.indexes = List.of(raw(changed));
            assertSafeFailure(fake, request(false)); assertEquals(0, fake.writes);
        }
        var missing = new Fake(); missing.indexes = List.of();
        assertSafeFailure(missing, request(false)); assertEquals(0, missing.writes);
    }

    @Test void indexEvidenceIsBoundedAndRecheckedEachTime() {
        var fake = new Fake();
        var tooMany = new ArrayList<RawBsonDocument>();
        for (int i = 0; i < 65; i++) tooMany.add(raw(index()));
        fake.indexes = tooMany;
        assertSafeFailure(fake, request(false)); assertEquals(0, fake.writes);
        fake.indexes = List.of(raw(index().append("private", "x".repeat(65536))));
        assertSafeFailure(fake, request(false)); assertEquals(0, fake.writes);
        fake.indexes = List.of(raw(index()));
        assertEquals(WriteResult.INSERTED, run(fake, request(false)));
        fake.indexes = List.of();
        assertSafeFailure(fake, request(false)); assertEquals(1, fake.writes);
    }

    @Test void sharedBudgetIncludesIndexLookupAndRejectsLateWriteCompletion() {
        var fake = new Fake(); fake.afterIndexes = () -> clock.set(500_000_000L);
        assertEquals(WriteResult.INSERTED, run(fake, request(false)));
        assertEquals(Duration.ofMillis(1500), fake.writeTimeout);
        clock.set(0); fake = new Fake(); fake.afterIndexes = () -> clock.set(2_000_000_000L);
        assertSafeFailure(fake, request(false)); assertEquals(0, fake.writes);
        clock.set(0); fake = new Fake(); fake.afterWrite = () -> clock.set(2_000_000_000L);
        assertSafeFailure(fake, request(false)); assertEquals(1, fake.writes);
    }

    @Test void invalidTimeoutAndUnacknowledgedConcernNeverDispatchWrite() {
        for (Duration timeout : Arrays.asList(null, Duration.ZERO, Duration.ofNanos(999999), Duration.ofSeconds(3), Duration.ofSeconds(-1))) {
            var fake = new Fake();
            assertThrows(RuntimeException.class, () -> adapter(fake).writeIfUnchanged(request(false), timeout).await().atMost(Duration.ofSeconds(3)));
            assertEquals(0, fake.writes);
        }
        var fake = new Fake(); fake.concern = WriteConcern.UNACKNOWLEDGED;
        assertSafeFailure(fake, request(false)); assertEquals(0, fake.writes);
    }

    @Test void nativeFailuresAndNullsStaySafeAndAreNeverRetried() {
        for (String mode : List.of("write_throw", "write_fail", "write_null", "ack_null", "index_throw", "index_fail", "index_null")) {
            var fake = new Fake(); fake.mode = mode;
            assertSafeFailure(fake, request(false)); assertTrue(fake.writes <= 1);
        }
    }

    @Test void productionConstructorUsesPrimaryBoundedDriverViewsAndOnlyRawWriteCommand() {
        var calls = new ArrayList<String>();
        var indexPublisher = proxy(com.mongodb.reactivestreams.client.ListIndexesPublisher.class, (object, method, args) -> {
            if (method.getName().equals("batchSize")) { assertEquals(8, args[0]); return object; }
            if (method.getName().equals("subscribe")) {
                calls.add("index_subscribe"); emit(args[0], raw(index())); return null;
            }
            throw new AssertionError(method.getName());
        });
        var collection = proxy(com.mongodb.reactivestreams.client.MongoCollection.class, (object, method, args) -> {
            return switch (method.getName()) {
                case "withReadPreference" -> { assertEquals(com.mongodb.ReadPreference.primary(), args[0]); yield object; }
                case "withTimeout" -> { assertClientTimeout(args); calls.add("index_timeout"); yield object; }
                case "listIndexes" -> { assertEquals(RawBsonDocument.class, args[0]); yield indexPublisher; }
                default -> throw new AssertionError(method.getName());
            };
        });
        var database = proxy(com.mongodb.reactivestreams.client.MongoDatabase.class, (object, method, args) -> {
            return switch (method.getName()) {
                case "getWriteConcern" -> WriteConcern.MAJORITY;
                case "getCollection" -> { assertEquals("summaries", args[0]); yield collection; }
                case "withTimeout" -> { assertClientTimeout(args); calls.add("write_timeout"); yield object; }
                case "runCommand" -> {
                    var command = (Document) args[0];
                    assertEquals("summaries", command.get("insert"));
                    assertFalse(command.containsKey("maxTimeMS"));
                    assertEquals(com.mongodb.ReadPreference.primary(), args[1]);
                    calls.add("raw_write");
                    yield (org.reactivestreams.Publisher<Document>) subscriber -> emit(subscriber,
                            new Document("ok", 1).append("n", 1));
                }
                default -> throw new AssertionError(method.getName());
            };
        });
        var nativeClient = proxy(com.mongodb.reactivestreams.client.MongoClient.class, (object, method, args) -> {
            assertEquals("getDatabase", method.getName()); assertEquals("synthetic", args[0]); return database;
        });
        var client = proxy(io.quarkus.mongodb.reactive.ReactiveMongoClient.class, (object, method, args) -> {
            assertEquals("unwrap", method.getName()); return nativeClient;
        });
        @SuppressWarnings("unchecked")
        jakarta.enterprise.inject.Instance<io.quarkus.mongodb.reactive.ReactiveMongoClient> instance =
                proxy(jakarta.enterprise.inject.Instance.class, (object, method, args) -> {
                    assertEquals("get", method.getName()); return client;
                });
        var adapter = new MongoConditionalProjectionRebuildWriteAdapter(instance, "synthetic", "summaries");
        assertTrue(calls.isEmpty());
        assertEquals(WriteResult.INSERTED, adapter.writeIfUnchanged(request(false), Duration.ofSeconds(2))
                .await().atMost(Duration.ofSeconds(3)));
        assertEquals(List.of("index_timeout", "index_subscribe", "write_timeout", "raw_write"), calls);
    }

    @Test void subMillisecondRemainderAndBackwardClockNeverDispatchWrite() {
        for (long time : new long[]{1_999_500_000L, -1}) {
            clock.set(0);
            var fake = new Fake(); fake.afterIndexes = () -> clock.set(time);
            assertSafeFailure(fake, request(false)); assertEquals(0, fake.writes);
        }
    }

    @Test void stalledMetadataAndWriteFailWithinTheirSharedTimeoutWithoutRetry() {
        for (String mode : List.of("index_stall", "write_stall")) {
            var fake = new Fake(); fake.mode = mode;
            var error = assertThrows(RuntimeException.class, () -> adapter(fake)
                    .writeIfUnchanged(request(false), Duration.ofMillis(40)).await().atMost(Duration.ofSeconds(3)));
            assertEquals("Conditional projection write unavailable", error.getMessage());
            assertEquals(mode.equals("index_stall") ? 0 : 1, fake.writes);
        }
    }

    private static void assertClientTimeout(Object[] args) {
        assertEquals(java.util.concurrent.TimeUnit.MILLISECONDS, args[1]);
        assertTrue((long) args[0] > 0 && (long) args[0] <= 2000);
    }
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    @SuppressWarnings("unchecked")
    private static <T> void emit(Object value, T item) {
        var subscriber = (org.reactivestreams.Subscriber<T>) value;
        subscriber.onSubscribe(new org.reactivestreams.Subscription() {
            boolean done;
            public void request(long count) { if (!done) { done = true; subscriber.onNext(item); subscriber.onComplete(); } }
            public void cancel() { done = true; }
        });
    }

    private MongoConditionalProjectionRebuildWriteAdapter adapter(Fake fake) {
        return new MongoConditionalProjectionRebuildWriteAdapter(fake, "summaries", clock::get);
    }
    private WriteResult run(Fake fake, WriteRequest request) {
        return adapter(fake).writeIfUnchanged(request, Duration.ofSeconds(2)).await().atMost(Duration.ofSeconds(3));
    }
    private void assertSafeFailure(Fake fake, WriteRequest request) {
        var error = assertThrows(RuntimeException.class, () -> run(fake, request));
        assertEquals("Conditional projection write unavailable", error.getMessage());
        assertNull(error.getCause());
    }
    private WriteRequest request(boolean stale) {
        var first = batch(0); var second = batch(1);
        var calculator = new BaselineTelemetryMetricsCalculator();
        var summary = calculator.calculate(stale ? List.of(first, second) : List.of(first));
        var identity = new AnalyticsProjectionIdentity(session, stream, "baseline-v1");
        return new WriteRequest(new StreamSummaryProjection(identity, stale ? 2 : 1, summary),
                stale ? new AnalyticsSummaryProjection(identity, 1, calculator.calculate(List.of(first))) : null);
    }
    private AdmittedTelemetryBatch batch(long sequence) {
        var time = Instant.parse("2026-09-18T10:00:00Z").plusSeconds(sequence);
        var batch = new AcceptedTelemetryBatch(1, UUID.randomUUID(), UUID.randomUUID(), session, stream,
                sequence, time, time.plusMillis(1), time.plusMillis(2), UUID.randomUUID(), null,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9)), List.of(), "synthetic-hash");
        return new AdmittedTelemetryBatch(batch, TelemetryAdmissionQuality.IN_ORDER);
    }
    private static Document index() {
        return new Document("name", "uq_analytics_stream_calculation").append("unique", true)
                .append("key", new Document("therapySessionId", 1).append("streamId", 1).append("calculationVersion", 1));
    }
    private static RawBsonDocument raw(Document value) { return new RawBsonDocument(value, new DocumentCodec()); }
    private static Document updateAck(int n, int modified) { return new Document("ok", 1).append("n", n).append("nModified", modified); }

    private static final class Fake implements MongoConditionalProjectionRebuildWriteAdapter.RawOperations {
        List<RawBsonDocument> indexes = List.of(raw(index()));
        final List<String> calls = new ArrayList<>();
        Document command, response = new Document("ok", 1).append("n", 1);
        WriteConcern concern = WriteConcern.MAJORITY;
        Duration writeTimeout;
        int writes;
        String mode = "ok";
        Runnable afterIndexes = () -> {}, afterWrite = () -> {};
        public WriteConcern writeConcern() { return concern; }
        public Multi<RawBsonDocument> indexes(Duration timeout) {
            calls.add("indexes");
            if (mode.equals("index_throw")) throw new IllegalStateException("private");
            if (mode.equals("index_null")) return null;
            if (mode.equals("index_fail")) return Multi.createFrom().failure(new IllegalStateException("private"));
            if (mode.equals("index_stall")) return Multi.createFrom().nothing();
            return Multi.createFrom().iterable(indexes).onCompletion().invoke(afterIndexes);
        }
        public Uni<Document> write(Document command, Duration timeout) {
            calls.add("write"); writes++; this.command = command; writeTimeout = timeout; afterWrite.run();
            if (mode.equals("write_throw")) throw new IllegalStateException("private");
            if (mode.equals("write_null")) return null;
            if (mode.equals("write_fail")) return Uni.createFrom().failure(new IllegalStateException("private"));
            if (mode.equals("write_stall")) return Uni.createFrom().nothing();
            if (mode.equals("ack_null")) return Uni.createFrom().nullItem();
            return Uni.createFrom().item(response);
        }
    }
}

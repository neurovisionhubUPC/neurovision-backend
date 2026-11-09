package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.*;
import com.mongodb.client.model.Collation;
import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultStore.Stored;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.*;
import io.smallrye.mutiny.Multi;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.reactivestreams.*;

class MongoCompensatorySaccadeTrialResultReaderAdapterTest {
    private static final UUID SESSION = new UUID(0, 1);
    private static final Duration WAIT = Duration.ofSeconds(2);
    private static Stored stored(String trial, Outcome outcome, DataOrigin origin) {
        return new Stored(new CompensatorySaccadeTrialContext(SESSION, new UUID(0, 2), 1, 500, 3000),
                new CompensatorySaccadeTrialResult(SESSION, trial, origin, outcome, outcome == Outcome.TIMEOUT ? 3000 : 1200,
                        outcome == Outcome.SUCCESS ? 500L : null));
    }
    private static final class Fake implements MongoCompensatorySaccadeTrialResultReaderAdapter.RawReader {
        int calls, limit; Bson filter;
        Multi<Document> response = Multi.createFrom().empty();
        public Multi<Document> find(Bson filter, int limit) {
            calls++; this.filter = filter; this.limit = limit; return response;
        }
    }
    private static MongoCompensatorySaccadeTrialResultReaderAdapter adapter(Fake fake) {
        return new MongoCompensatorySaccadeTrialResultReaderAdapter(fake, Duration.ofMillis(100));
    }

    @Test void deferredReadFiltersSessionAndOriginAndReturnsImmutableStrictlyDecodedRows() {
        for (var origin : DataOrigin.values()) {
            var f = new Fake(); var expected = new ArrayList<Stored>();
            for (var outcome : Outcome.values()) expected.add(stored(" opaque /:$ é " + outcome, outcome, origin));
            var documents = expected.stream().map(MongoCompensatorySaccadeTrialResultStoreAdapter::document).toList();
            var before = documents.stream().map(Document::toJson).toList();
            f.response = Multi.createFrom().iterable(documents);
            var query = adapter(f).read(SESSION, origin, 1001); assertEquals(0, f.calls);
            var rows = query.await().atMost(WAIT);
            assertEquals(expected, rows); assertEquals(1, f.calls); assertEquals(1001, f.limit);
            assertEquals(new Document("$and", List.of(new Document("_id.therapySessionId", SESSION.toString()),
                    new Document("result.dataOrigin", origin.name()))), bson(f.filter));
            assertThrows(UnsupportedOperationException.class, () -> rows.add(expected.getFirst()));
            assertEquals(before, documents.stream().map(Document::toJson).toList());
        }
        var f = new Fake(); assertTrue(adapter(f).read(SESSION, DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT).isEmpty());
    }

    @Test void sourceLimitIsNotSilentClientTruncationAndOversizedPublishersAreCancelled() {
        for (int limit : List.of(1, 1000, 1001)) {
            var f = new Fake(); var doc = MongoCompensatorySaccadeTrialResultStoreAdapter.document(stored("a", Outcome.SUCCESS, DataOrigin.SYNTHETIC));
            f.response = Multi.createFrom().iterable(Collections.nCopies(limit, doc));
            assertEquals(limit, adapter(f).read(SESSION, DataOrigin.SYNTHETIC, limit).await().atMost(WAIT).size());
            var seen = new java.util.concurrent.atomic.AtomicInteger(); var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            f.response = Multi.createFrom().items(() -> java.util.stream.Stream.generate(() -> { seen.incrementAndGet(); return doc; }))
                    .onCancellation().invoke(() -> cancelled.set(true));
            safeFailure(() -> adapter(f).read(SESSION, DataOrigin.SYNTHETIC, limit).await().atMost(WAIT));
            assertEquals(limit + 1, seen.get()); assertTrue(cancelled.get());
        }
    }

    @Test void corruptForeignAndMixedOriginDocumentsFailWithoutReturningAPartialList() {
        for (int kind = 0; kind < 8; kind++) {
            var f = new Fake(); var valid = MongoCompensatorySaccadeTrialResultStoreAdapter.document(stored("a", Outcome.SUCCESS, DataOrigin.SYNTHETIC));
            var bad = MongoCompensatorySaccadeTrialResultStoreAdapter.document(stored("b", Outcome.SUCCESS, DataOrigin.SYNTHETIC));
            switch (kind) {
                case 0 -> bad.put("storageSchemaVersion", 2);
                case 1 -> bad.get("_id", Document.class).put("therapySessionId", new UUID(0, 9).toString());
                case 2 -> bad.get("result", Document.class).put("dataOrigin", "PRODUCER_REPORTED");
                case 3 -> bad.get("context", Document.class).put("configurationVersion", 1.0);
                case 4 -> bad.get("result", Document.class).put("confirmedDwellMs", null);
                case 5 -> bad.put("extra", "private");
                case 6 -> bad.put("_id", "private");
                default -> bad.get("_id", Document.class).put("trialId", " ");
            }
            var before = bad.toJson(); f.response = Multi.createFrom().items(valid, bad);
            safeFailure(() -> adapter(f).read(SESSION, DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT));
            assertEquals(before, bad.toJson()); assertEquals(1, f.calls);
        }
    }

    @Test void timeoutAndTransportFailuresAreRedactedWithoutRetriesAndInvalidRequestsNeverDispatch() {
        for (boolean timeout : List.of(false, true)) {
            var f = new Fake(); f.response = timeout ? Multi.createFrom().nothing()
                    : Multi.createFrom().failure(new IllegalStateException("private"));
            safeFailure(() -> adapter(f).read(SESSION, DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT));
            assertEquals(1, f.calls);
        }
        for (boolean throwing : List.of(false, true)) {
            var a = new MongoCompensatorySaccadeTrialResultReaderAdapter((filter, limit) -> {
                if (throwing) throw new IllegalStateException("private"); return null;
            }, Duration.ofMillis(100));
            safeFailure(() -> a.read(SESSION, DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT));
        }
        var f = new Fake();
        for (int limit : List.of(-1, 0, 1002, Integer.MAX_VALUE))
            safeFailure(() -> adapter(f).read(SESSION, DataOrigin.SYNTHETIC, limit).await().atMost(WAIT));
        safeFailure(() -> adapter(f).read(null, DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT));
        safeFailure(() -> adapter(f).read(SESSION, null, 1001).await().atMost(WAIT));
        assertEquals(0, f.calls);
        for (long ms : List.of(99L, 10001L))
            assertThrows(IllegalArgumentException.class, () -> new MongoCompensatorySaccadeTrialResultReaderAdapter(f, Duration.ofMillis(ms)));
    }

    @Test void productionDriverUsesSourceSideFilterLimitSimpleCollationAndPrimaryMajorityReadOnly() {
        var calls = new ArrayList<String>();
        var doc = MongoCompensatorySaccadeTrialResultStoreAdapter.document(stored("a", Outcome.SUCCESS, DataOrigin.SYNTHETIC));
        @SuppressWarnings("unchecked") var find = proxy(com.mongodb.reactivestreams.client.FindPublisher.class, (o, m, a) -> {
            switch (m.getName()) {
                case "limit", "batchSize" -> { assertEquals(1001, a[0]); calls.add(m.getName()); return o; }
                case "collation" -> { assertEquals(Collation.builder().locale("simple").build(), a[0]); calls.add("collation"); return o; }
                case "subscribe" -> { calls.add("subscribe"); publisher(doc).subscribe((Subscriber<? super Document>) a[0]); return null; }
                default -> throw new AssertionError("Unexpected find operation: " + m.getName());
            }
        });
        @SuppressWarnings("unchecked") var collection = proxy(com.mongodb.reactivestreams.client.MongoCollection.class, (o, m, a) -> {
            switch (m.getName()) {
                case "withReadPreference" -> assertEquals(ReadPreference.primary(), a[0]);
                case "withReadConcern" -> assertEquals(ReadConcern.MAJORITY, a[0]);
                case "withTimeout" -> { assertEquals(100L, a[0]); assertEquals(TimeUnit.MILLISECONDS, a[1]); }
                case "find" -> {
                    assertEquals(new Document("$and", List.of(new Document("_id.therapySessionId", SESSION.toString()),
                            new Document("result.dataOrigin", "SYNTHETIC"))), bson((Bson) a[0])); return find;
                }
                default -> throw new AssertionError("Unexpected collection operation: " + m.getName());
            }
            calls.add(m.getName()); return o;
        });
        var db = proxy(com.mongodb.reactivestreams.client.MongoDatabase.class, (o, m, a) -> {
            assertEquals("getCollection", m.getName()); assertEquals("results", a[0]); return collection;
        });
        var nativeClient = proxy(com.mongodb.reactivestreams.client.MongoClient.class, (o, m, a) -> {
            assertEquals("getDatabase", m.getName()); assertEquals("synthetic", a[0]); return db;
        });
        var client = proxy(io.quarkus.mongodb.reactive.ReactiveMongoClient.class, (o, m, a) -> {
            assertEquals("unwrap", m.getName()); return nativeClient;
        });
        @SuppressWarnings("unchecked") jakarta.enterprise.inject.Instance<io.quarkus.mongodb.reactive.ReactiveMongoClient> clients =
                proxy(jakarta.enterprise.inject.Instance.class, (o, m, a) -> { assertEquals("get", m.getName()); return client; });
        var adapter = new MongoCompensatorySaccadeTrialResultReaderAdapter(clients, "synthetic", "results", 100);
        assertTrue(calls.isEmpty());
        assertEquals(1, adapter.read(SESSION, DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT).size());
        assertEquals(List.of("withReadPreference", "withReadConcern", "withTimeout", "limit", "batchSize", "collation", "subscribe"), calls);
    }

    @Test void readsIndependentCompensatorySaccadeStorageShapeAndRejectsSiblingFieldNames() {
        var document = new Document("_id", new Document("therapySessionId", SESSION.toString()).append("trialId", "opaque"))
                .append("storageSchemaVersion", 1)
                .append("context", new Document("configurationId", new UUID(0, 2).toString())
                        .append("configurationVersion", 1L).append("minimumFixationMs", 500L)
                        .append("maximumStimulusDurationMs", 3000L))
                .append("result", new Document("outcome", "SUCCESS")
                        .append("elapsedMs", 1200L).append("confirmedDwellMs", 500L)
                        .append("dataOrigin", "SYNTHETIC"));
        var f = new Fake(); f.response = Multi.createFrom().item(document);
        assertEquals(List.of(stored("opaque", Outcome.SUCCESS, DataOrigin.SYNTHETIC)),
                adapter(f).read(SESSION, DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT));
        for (boolean contextField : List.of(false, true)) {
            var legacy = Document.parse(document.toJson());
            var section = legacy.get(contextField ? "context" : "result", Document.class);
            var value = section.remove(contextField ? "maximumStimulusDurationMs" : "elapsedMs");
            section.put(contextField ? "maximumFixationMs" : "localizationDurationMs", value);
            f.response = Multi.createFrom().item(legacy);
            safeFailure(() -> adapter(f).read(SESSION, DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT));
        }
    }

    private static Document bson(Bson value) {
        return Document.parse(value.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()).toJson());
    }
    private static void safeFailure(org.junit.jupiter.api.function.Executable operation) {
        var error = assertThrows(IllegalStateException.class, operation);
        assertEquals("Compensatory Saccade result read unavailable", error.getMessage()); assertNull(error.getCause());
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private static Publisher<Document> publisher(Document document) {
        return subscriber -> subscriber.onSubscribe(new Subscription() {
            boolean done;
            public void request(long n) { if (!done) { done = true; subscriber.onNext(document); subscriber.onComplete(); } }
            public void cancel() { done = true; }
        });
    }
}

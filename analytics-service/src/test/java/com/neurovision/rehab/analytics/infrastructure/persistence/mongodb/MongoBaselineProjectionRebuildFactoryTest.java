package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.neurovision.rehab.analytics.application.internal.commandservices.ApplyBaselineProjectionRebuildService;
import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.*;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.*;
import org.bson.codecs.DocumentCodec;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.reactivestreams.*;

class MongoBaselineProjectionRebuildFactoryTest {
    private static final UUID SESSION = new UUID(0, 101), STREAM = new UUID(0, 102);
    private static final String DB = "synthetic-db", INBOX = "synthetic-inbox", SUMMARY = "synthetic-summary";

    @Test void constructionAndDeniedOrUnsupportedRequestsNeverResolveTheClient() {
        var mongo = new Mongo();
        var service = service(mongo);
        assertEquals(0, mongo.clientCalls);
        assertEquals("not_authorized", apply(service, false).status().code());
        assertEquals("refused", service.apply(SESSION, STREAM, "unsupported", true).await().indefinitely().status().code());
        assertEquals(0, mongo.clientCalls);
        assertTrue(mongo.calls.isEmpty());
    }

    @Test void missingProjectionIsCalculatedInsertedAndFreshlyObservedAsNoChange() {
        var mongo = new Mongo();
        var sourceBefore = mongo.sources.toString();
        var otherBefore = copy(mongo.unrelated);
        var service = service(mongo);
        assertEquals("applied", apply(service, true).status().code());
        assertEquals(List.of("P", "S", "S", "P", "I", "W"), mongo.calls);
        assertEquals(projection(mongo.sources), mongo.projection);
        assertEquals("no_change", apply(service, true).status().code());
        assertEquals(List.of("P", "S", "S", "P", "I", "W", "P", "S", "S", "P"), mongo.calls);
        assertEquals(1, mongo.writes);
        assertEquals(sourceBefore, mongo.sources.toString());
        assertEquals(otherBefore, mongo.unrelated);
    }

    @Test void staleProjectionUsesFullObservedCasAndPreservesExtraFields() {
        var mongo = new Mongo();
        mongo.sources.add(source(1));
        mongo.projection = projection(mongo.sources.subList(0, 1)).append("fixtureExtra", "preserve");
        var expected = copy(mongo.projection);
        var inboxBefore = mongo.sources.toString();
        var service = service(mongo);
        assertEquals("applied", apply(service, true).status().code());
        assertEquals(identity().append("admittedBatchCount", expected.get("admittedBatchCount"))
                .append("summary", expected.get("summary")), mongo.command.getList("updates", Document.class).getFirst().get("q"));
        var desired = projection(mongo.sources).append("fixtureExtra", "preserve");
        assertEquals(desired, mongo.projection);
        assertEquals("no_change", apply(service, true).status().code());
        assertEquals(1, mongo.writes);
        assertEquals(inboxBefore, mongo.sources.toString());
    }

    @Test void interveningInsertOrRevisionChangeReturnsConflictWithoutOverwrite() {
        for (boolean missing : List.of(true, false)) {
            var mongo = new Mongo();
            mongo.sources.add(source(1));
            if (!missing) mongo.projection = projection(mongo.sources.subList(0, 1));
            var concurrent = projection(mongo.sources).append("admittedBatchCount", 3L).append("fixtureExtra", "winner");
            var inboxBefore = mongo.sources.toString();
            mongo.beforeWrite = () -> mongo.projection = copy(concurrent);
            assertEquals("conflict", apply(service(mongo), true).status().code());
            assertEquals(concurrent, mongo.projection);
            assertEquals(1, mongo.writes);
            assertEquals(inboxBefore, mongo.sources.toString());
        }
    }

    @Test void committedButLostAcknowledgementStaysUnknownAndNeverResends() {
        var mongo = new Mongo();
        var service = service(mongo);
        var inboxBefore = mongo.sources.toString();
        mongo.loseAcknowledgement = true;
        assertEquals("write_outcome_unknown", apply(service, true).status().code());
        assertEquals(projection(mongo.sources), mongo.projection);
        assertEquals(1, mongo.writes);
        assertEquals("no_change", apply(service, true).status().code());
        assertEquals(1, mongo.writes);
        assertEquals(inboxBefore, mongo.sources.toString());
    }

    @Test void invalidOrChangingSourceRefusesBeforeAnyIndexScanOrWrite() {
        for (boolean invalid : List.of(true, false)) {
            var mongo = new Mongo();
            if (invalid) mongo.sources.getFirst().put("status", "invalid");
            else mongo.changeSource = true;
            var outcome = apply(service(mongo), true);
            assertEquals("refused", outcome.status().code());
            assertEquals(invalid ? "source_invalid" : "source_changed", outcome.observation().status().code());
            assertEquals(0, mongo.writes);
            assertFalse(mongo.calls.contains("I"));
            assertNull(mongo.projection);
        }
    }

    @Test void invalidLocationsAreRejectedBeforeAnyClientResolution() {
        var mongo = new Mongo();
        for (String[] names : List.of(new String[] {"", INBOX, SUMMARY}, new String[] {DB, " ", SUMMARY},
                new String[] {DB, INBOX, ""}, new String[] {DB, INBOX, INBOX})) {
            assertThrows(IllegalArgumentException.class,
                    () -> MongoBaselineProjectionRebuildFactory.create(mongo.clients(), names[0], names[1], names[2]));
        }
        assertThrows(NullPointerException.class, () -> MongoBaselineProjectionRebuildFactory.create(null, DB, INBOX, SUMMARY));
        assertEquals(0, mongo.clientCalls);
    }

    private static ApplyBaselineProjectionRebuildService service(Mongo mongo) {
        return MongoBaselineProjectionRebuildFactory.create(mongo.clients(), DB, INBOX, SUMMARY);
    }

    private static ApplyBaselineProjectionRebuildService.Outcome apply(ApplyBaselineProjectionRebuildService service, boolean permitted) {
        return service.apply(SESSION, STREAM, "baseline-v1", permitted).await().atMost(Duration.ofSeconds(6));
    }

    /** Fake driver boundary only; real mappers, ports, adapters, calculation and application service run above it. */
    private static final class Mongo {
        final List<Document> sources = new ArrayList<>(List.of(source(0)));
        final List<String> calls = new ArrayList<>();
        final Document unrelated = new Document("fixtureSentinel", "unchanged");
        Document projection, command;
        int clientCalls, writes, sourceReads;
        boolean loseAcknowledgement, changeSource;
        Runnable beforeWrite = () -> { };

        Instance<ReactiveMongoClient> clients() {
            ReactiveMongoClient client = proxy(ReactiveMongoClient.class, (object, method, args) -> switch (method.getName()) {
                case "getDatabase" -> { assertEquals(DB, args[0]); yield database(); }
                case "unwrap" -> nativeClient();
                default -> throw new AssertionError(method.getName());
            });
            return proxy(Instance.class, (object, method, args) -> {
                assertEquals("get", method.getName()); clientCalls++; return client;
            });
        }

        ReactiveMongoDatabase database() {
            return proxy(ReactiveMongoDatabase.class, (object, method, args) -> {
                assertEquals("getCollection", method.getName());
                assertEquals(RawBsonDocument.class, args[1]);
                String name = (String) args[0];
                assertTrue(List.of(INBOX, SUMMARY).contains(name));
                return proxy(ReactiveMongoCollection.class, (collection, find, parameters) -> {
                    assertEquals("find", find.getName());
                    boolean source = name.equals(INBOX);
                    var filter = (Bson) parameters[0];
                    var expected = new Document("therapySessionId", SESSION.toString()).append("streamId", STREAM.toString());
                    if (!source) expected.append("calculationVersion", "baseline-v1");
                    // Filters.and preserves each identity term; never allow a cross-stream query.
                    var terms = expected.entrySet().stream().map(e -> new Document(e.getKey(), e.getValue())).toList();
                    assertEquals(new Document("$and", terms).toBsonDocument(), filter.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()));
                    checkFind((FindOptions) parameters[1], source);
                    calls.add(source ? "S" : "P");
                    if (source && ++sourceReads == 2 && changeSource) sources.add(source(1));
                    var rows = source ? sources : projection == null ? List.<Document>of() : List.of(projection);
                    return Multi.createFrom().iterable(rows.stream().map(MongoBaselineProjectionRebuildFactoryTest::raw).toList());
                });
            });
        }

        com.mongodb.reactivestreams.client.MongoClient nativeClient() {
            return proxy(com.mongodb.reactivestreams.client.MongoClient.class, (object, method, args) -> {
                assertEquals("getDatabase", method.getName()); assertEquals(DB, args[0]);
                return proxy(com.mongodb.reactivestreams.client.MongoDatabase.class, (database, operation, values) -> switch (operation.getName()) {
                    case "getWriteConcern" -> WriteConcern.MAJORITY;
                    case "withTimeout" -> { timeout(values); yield database; }
                    case "getCollection" -> { assertEquals(SUMMARY, values[0]); yield indexes(); }
                    case "runCommand" -> {
                        assertEquals(ReadPreference.primary(), values[1]);
                        writes++; calls.add("W"); command = copy((Document) values[0]); beforeWrite.run();
                        Document ack = execute(command);
                        yield (Publisher<Document>) subscriber -> subscriber.onSubscribe(new Subscription() {
                            final AtomicBoolean sent = new AtomicBoolean();
                            public void request(long n) {
                                if (sent.compareAndSet(false, true)) {
                                    if (loseAcknowledgement) subscriber.onError(new IllegalStateException("synthetic-lost-ack"));
                                    else { subscriber.onNext(ack); subscriber.onComplete(); }
                                }
                            }
                            public void cancel() { sent.set(true); }
                        });
                    }
                    default -> throw new AssertionError(operation.getName());
                });
            });
        }

        Object indexes() {
            return proxy(com.mongodb.reactivestreams.client.MongoCollection.class, (object, method, args) -> switch (method.getName()) {
                case "withReadPreference" -> { assertEquals(ReadPreference.primary(), args[0]); yield object; }
                case "withTimeout" -> { timeout(args); yield object; }
                case "listIndexes" -> {
                    assertEquals(RawBsonDocument.class, args[0]);
                    yield proxy(com.mongodb.reactivestreams.client.ListIndexesPublisher.class, (publisher, call, values) -> {
                        if (call.getName().equals("batchSize")) { assertEquals(8, values[0]); return publisher; }
                        assertEquals("subscribe", call.getName()); calls.add("I");
                        @SuppressWarnings("unchecked") var subscriber = (Subscriber<RawBsonDocument>) values[0];
                        subscriber.onSubscribe(new Subscription() {
                            final AtomicBoolean sent = new AtomicBoolean();
                            public void request(long n) {
                                if (sent.compareAndSet(false, true)) {
                                    subscriber.onNext(raw(new Document("name", "uq_analytics_stream_calculation")
                                            .append("unique", true).append("key", new Document("therapySessionId", 1)
                                                    .append("streamId", 1).append("calculationVersion", 1))));
                                    subscriber.onComplete();
                                }
                            }
                            public void cancel() { sent.set(true); }
                        }); return null;
                    });
                }
                default -> throw new AssertionError(method.getName());
            });
        }

        Document execute(Document value) {
            assertEquals(Boolean.TRUE, value.get("ordered"));
            assertEquals(WriteConcern.MAJORITY.asDocument(), value.get("writeConcern", Document.class).toBsonDocument());
            if (value.containsKey("insert")) {
                assertEquals(SUMMARY, value.get("insert"));
                assertEquals(Set.of("insert", "documents", "ordered", "writeConcern"), value.keySet());
                assertEquals(1, value.getList("documents", Document.class).size());
                if (projection != null) return new Document("ok", 1).append("n", 0).append("writeErrors",
                        List.of(new Document("index", 0).append("code", 11000)));
                projection = copy(value.getList("documents", Document.class).getFirst());
                return new Document("ok", 1).append("n", 1);
            }
            assertEquals(Set.of("update", "updates", "ordered", "writeConcern"), value.keySet());
            assertEquals(SUMMARY, value.get("update"));
            var update = value.getList("updates", Document.class).getFirst();
            assertEquals(Set.of("q", "u", "multi", "upsert", "collation"), update.keySet());
            assertEquals(false, update.get("multi")); assertEquals(false, update.get("upsert"));
            assertEquals(new Document("locale", "simple"), update.get("collation"));
            var query = update.get("q", Document.class);
            assertEquals(Set.of("therapySessionId", "streamId", "calculationVersion", "admittedBatchCount", "summary"), query.keySet());
            boolean match = projection != null && query.entrySet().stream().allMatch(e -> Objects.equals(e.getValue(), projection.get(e.getKey())));
            if (match) projection.putAll(update.get("u", Document.class).get("$set", Document.class));
            return new Document("ok", 1).append("n", match ? 1 : 0).append("nModified", match ? 1 : 0);
        }
    }

    private static void timeout(Object[] args) {
        assertTrue(((Number) args[0]).longValue() > 0 && ((Number) args[0]).longValue() <= 2000);
        assertEquals(TimeUnit.MILLISECONDS, args[1]);
    }

    private static void checkFind(FindOptions options, boolean source) {
        var applied = new HashMap<String, Object[]>();
        var publisher = proxy(com.mongodb.reactivestreams.client.FindPublisher.class, (object, method, args) -> {
            applied.put(method.getName(), args); return object;
        });
        options.apply(publisher);
        assertEquals(source ? 65 : 2, applied.get("limit")[0]);
        assertEquals(source ? 8 : 2, applied.get("batchSize")[0]);
        timeout(applied.get("maxTime"));
        assertNotNull(applied.get("projection"));
        assertEquals(source, applied.containsKey("sort"));
    }

    private static Document source(long sequence) {
        var time = Instant.parse("2026-09-18T10:00:00Z").plusSeconds(sequence);
        var batch = new AcceptedTelemetryBatch(1, new UUID(0, 200 + sequence), new UUID(0, 300 + sequence), SESSION, STREAM, sequence,
                time, time.plusMillis(1), time.plusMillis(2), new UUID(0, 400 + sequence), "synthetic-trace",
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9)), List.of(), "synthetic-hash");
        return MongoTelemetryInboxAdapter.admittedDocument(batch, TelemetryAdmissionQuality.IN_ORDER,
                new TelemetryInboxPort.Provenance("synthetic-topic", 0, sequence, 128), time);
    }

    private static Document identity() {
        return new Document("therapySessionId", SESSION.toString()).append("streamId", STREAM.toString()).append("calculationVersion", "baseline-v1");
    }
    private static Document projection(List<Document> source) {
        var summary = new BaselineTelemetryMetricsCalculator().calculate(source.stream().map(MongoTelemetryInboxAdapter::reconstruct).toList());
        return identity().append("admittedBatchCount", (long) source.size()).append("summary", MongoStreamSummaryProjectionAdapter.summaryDocument(summary));
    }
    private static RawBsonDocument raw(Document value) { return new RawBsonDocument(value, new DocumentCodec()); }
    private static Document copy(Document value) { return raw(value).decode(new DocumentCodec()); }
    @FunctionalInterface private interface Handler { Object call(Object object, Method method, Object[] args); }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler::call);
    }
}

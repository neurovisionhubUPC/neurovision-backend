package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.analytics.application.ports.out.persistence.ProjectionRebuildObservationPort.*;
import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoDatabase;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import jakarta.enterprise.inject.Instance;
import io.smallrye.mutiny.Multi;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.reactivestreams.client.FindPublisher;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.bson.*;
import org.bson.codecs.DocumentCodec;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

class MongoProjectionRebuildObservationAdapterTest {
    private final UUID session = UUID.randomUUID();
    private final UUID stream = UUID.randomUUID();
    private final TelemetryStreamIdentity identity = new TelemetryStreamIdentity(session, stream);
    private final ReadBounds sourceBounds = new ReadBounds(65, 131072, 4194304, Duration.ofSeconds(2));
    private final ReadBounds projectionBounds = new ReadBounds(2, 262144, 524288, Duration.ofSeconds(2));

    @Test void sourceUsesExactFilterAndLosslessExistingMapperWithAttestedBsonBytes() {
        var document = source(0);
        var raw = raw(document);
        var calls = new ArrayList<String>();
        var adapter = new MongoProjectionRebuildObservationAdapter((kind, filter, options) -> {
            assertEquals(MongoProjectionRebuildObservationAdapter.CollectionKind.SOURCE, kind);
            var bson = filter.toBsonDocument();
            assertEquals(2, bson.getArray("$and").size());
            assertFalse(bson.toString().contains("ADMITTED"));
            calls.add("find");
            return Multi.createFrom().item(raw);
        });
        var result = adapter.readSource(identity, sourceBounds).await().indefinitely();
        assertEquals(List.of("find"), calls);
        assertEquals(1, result.size());
        assertEquals(MongoTelemetryInboxAdapter.reconstruct(document), result.getFirst().admitted());
        assertEquals(raw.getByteBuffer().remaining(), result.getFirst().encodedProjectedBsonBytes());
        assertEquals(2, result.getFirst().inboxSchemaVersion());
    }

    @Test void exactFindOptionsAreAppliedWithoutMongo() {
        for (boolean source : List.of(true, false)) {
            var adapter = new MongoProjectionRebuildObservationAdapter((kind, filter, options) -> {
                var applied = options(options);
                assertEquals(source ? 65 : 2, applied.get("limit")[0]);
                assertEquals(source ? 8 : 2, applied.get("batchSize")[0]);
                assertEquals(1234L, applied.get("maxTime")[0]);
                assertEquals(TimeUnit.MILLISECONDS, applied.get("maxTime")[1]);
                var projection = ((Bson) applied.get("projection")[0]).toBsonDocument();
                assertEquals(new BsonInt32(0), projection.get("_id"));
                assertEquals(source ? 18 : 6, projection.size());
                assertEquals(new BsonInt32(1), projection.get(source ? "admissionQuality" : "summary"));
                assertFalse(projection.containsKey("admittedAt"));
                assertFalse(projection.containsKey("source"));
                if (source) assertEquals(BsonDocument.parse("{sequence:1,batchId:1}"), ((Bson) applied.get("sort")[0]).toBsonDocument());
                else {
                    assertFalse(applied.containsKey("sort"));
                    assertTrue(filter.toBsonDocument().toString().contains("baseline-v1"));
                }
                return Multi.createFrom().empty();
            });
            var bounds = new ReadBounds(source ? 65 : 2, source ? 131072 : 262144,
                    source ? 4194304 : 524288, Duration.ofNanos(1234999999));
            if (source) adapter.readSource(identity, bounds).await().indefinitely();
            else adapter.readProjection(projectionIdentity(), bounds).await().indefinitely();
        }
    }

    @Test void numericSchemaAndLegacyMappingMatchExistingReconstruction() {
        for (boolean legacy : List.of(true, false)) {
            var doc = source(0);
            if (legacy) { doc.remove("inboxSchemaVersion"); doc.remove("correlationId"); }
            else doc.put("inboxSchemaVersion", 2L);
            var result = adapter(List.of(raw(doc))).readSource(identity, sourceBounds).await().indefinitely().getFirst();
            assertEquals(MongoTelemetryInboxAdapter.reconstruct(doc), result.admitted());
            assertEquals(legacy ? null : 2, result.inboxSchemaVersion());
            assertEquals(TelemetryAdmissionQuality.LATE_ARRIVAL, result.admitted().admissionQuality());
        }
    }

    @Test void sentinelAndDuplicateDetectionCannotCertifyTruncatedReads() {
        var rows = new ArrayList<RawBsonDocument>();
        for (int i = 0; i < 64; i++) rows.add(raw(source(i)));
        assertEquals(64, adapter(rows).readSource(identity, sourceBounds).await().indefinitely().size());
        var counter = new AtomicInteger();
        var sixtyFive = new MongoProjectionRebuildObservationAdapter((kind, filter, options) ->
                Multi.createFrom().range(0, 100000).onItem().transform(i -> { counter.incrementAndGet(); return raw(source(i)); }));
        var exceeded = assertThrows(ObservationLimitException.class,
                () -> sixtyFive.readSource(identity, sourceBounds).await().indefinitely());
        assertEquals(ObservationLimit.SOURCE_BATCHES, exceeded.limit());
        assertEquals(65, counter.get());
        var projection = projection();
        var duplicates = assertThrows(ObservationException.class,
                () -> adapter(List.of(raw(projection), raw(projection))).readProjection(projectionIdentity(), projectionBounds).await().indefinitely());
        assertEquals(ReadFailure.PROJECTION_INVALID, duplicates.failure());
    }

    @Test void byteLimitsAreInclusiveAndCheckedBeforeMalformedReconstruction() {
        var document = source(0);
        var bytes = raw(document).getByteBuffer().remaining();
        var exact = new ReadBounds(65, bytes, bytes, Duration.ofSeconds(1));
        assertEquals(bytes, adapter(List.of(raw(document))).readSource(identity, exact).await().indefinitely().getFirst().encodedProjectedBsonBytes());
        var tooSmall = new ReadBounds(65, bytes - 1, bytes, Duration.ofSeconds(1));
        assertEquals(ObservationLimit.SOURCE_DOCUMENT_BYTES, assertThrows(ObservationLimitException.class,
                () -> adapter(List.of(raw(document))).readSource(identity, tooSmall).await().indefinitely()).limit());
        assertEquals(ObservationLimit.SOURCE_PASS_BYTES, assertThrows(ObservationLimitException.class,
                () -> adapter(List.of(raw(document), raw(document))).readSource(identity, exact).await().indefinitely()).limit());
        var malformedOversized = raw(new Document("padding", "x".repeat(131073)));
        assertEquals(ObservationLimit.SOURCE_DOCUMENT_BYTES, assertThrows(ObservationLimitException.class,
                () -> adapter(List.of(malformedOversized)).readSource(identity, sourceBounds).await().indefinitely()).limit());
        var projection = raw(projection());
        var pbytes = projection.getByteBuffer().remaining();
        var pexact = new ReadBounds(2, pbytes, pbytes, Duration.ofSeconds(1));
        assertEquals(pbytes, adapter(List.of(projection)).readProjection(projectionIdentity(), pexact).await().indefinitely().getFirst().encodedProjectedBsonBytes());
        assertEquals(ObservationLimit.PROJECTION_DOCUMENT_BYTES, assertThrows(ObservationLimitException.class,
                () -> adapter(List.of(projection)).readProjection(projectionIdentity(),
                        new ReadBounds(2, pbytes - 1, pbytes, Duration.ofSeconds(1))).await().indefinitely()).limit());
    }

    @Test void combinedSampleBoundRejectsWithoutTruncation() {
        var document = source(0);
        document.put("gazeSamples", Collections.nCopies(1000, new Document("offsetMs", 0L).append("x", .2).append("y", .3).append("confidence", .9)));
        assertEquals(1000, adapter(List.of(raw(document))).readSource(identity, sourceBounds).await().indefinitely()
                .getFirst().admitted().batch().gazeSamples().size());
        document.put("gameplaySamples", List.of(new Document()));
        assertEquals(ObservationLimit.BATCH_SAMPLES, assertThrows(ObservationLimitException.class,
                () -> adapter(List.of(raw(document))).readSource(identity, sourceBounds).await().indefinitely()).limit());
    }

    @Test void malformedMatchingRowsFailInsteadOfBeingFilteredOrSkipped() {
        for (String field : List.of("status", "inboxSchemaVersion", "therapySessionId", "gazeSamples", "payloadSha256", "admissionQuality")) {
            var document = source(0);
            document.put(field, "payloadSha256".equals(field) ? "" : "invalid");
            var failure = assertThrows(ObservationException.class,
                    () -> adapter(List.of(raw(document))).readSource(identity, sourceBounds).await().indefinitely());
            assertEquals(ReadFailure.SOURCE_INVALID, failure.failure(), field);
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains(session.toString()));
        }
        var wrongIdentity = source(0);
        wrongIdentity.put("therapySessionId", UUID.randomUUID().toString());
        assertEquals(ReadFailure.SOURCE_INVALID, assertThrows(ObservationException.class,
                () -> adapter(List.of(raw(wrongIdentity))).readSource(identity, sourceBounds).await().indefinitely()).failure());
        var projection = projection();
        projection.remove("summary");
        assertEquals(ReadFailure.PROJECTION_INVALID, assertThrows(ObservationException.class,
                () -> adapter(List.of(raw(projection))).readProjection(projectionIdentity(), projectionBounds).await().indefinitely()).failure());
    }

    @Test void incompleteFailedStreamNeverReturnsPartialSuccessOrRawCause() {
        var queries = new AtomicInteger();
        var adapter = new MongoProjectionRebuildObservationAdapter((kind, filter, options) -> {
            queries.incrementAndGet();
            return Multi.createBy().concatenating().streams(Multi.createFrom().item(raw(source(0))),
                    Multi.createFrom().failure(new IllegalStateException("private-document")));
        });
        var failure = assertThrows(ObservationException.class, () -> adapter.readSource(identity, sourceBounds).await().indefinitely());
        assertEquals(ReadFailure.UNAVAILABLE, failure.failure());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("private-document"));
        assertEquals(1, queries.get());
    }

    @Test void invalidBoundsAndSubMillisecondBudgetsDoNotDispatch() {
        var calls = new AtomicInteger();
        var adapter = new MongoProjectionRebuildObservationAdapter((kind, filter, options) -> { calls.incrementAndGet(); return Multi.createFrom().empty(); });
        for (var bounds : Arrays.asList(null, new ReadBounds(64, 131072, 4194304, Duration.ofSeconds(1)),
                new ReadBounds(66, 131072, 4194304, Duration.ofSeconds(1)),
                new ReadBounds(65, 131073, 4194304, Duration.ofSeconds(1)),
                new ReadBounds(65, 131072, 4194305, Duration.ofSeconds(1)))) {
            assertEquals(ReadFailure.UNAVAILABLE, assertThrows(ObservationException.class,
                    () -> adapter.readSource(identity, bounds).await().indefinitely()).failure());
        }
        assertThrows(ObservationException.class, () -> adapter.readProjection(projectionIdentity(), sourceBounds).await().indefinitely());
        assertThrows(ObservationDeadlineException.class, () -> adapter.readSource(identity,
                new ReadBounds(65, 131072, 4194304, Duration.ofNanos(999999))).await().indefinitely());
        assertEquals(0, calls.get());
    }

    @Test void asyncAndServerTimeoutsHaveFixedDeadlineFailureWithoutRetry() {
        var calls = new AtomicInteger();
        var stalled = new MongoProjectionRebuildObservationAdapter((kind, filter, options) -> { calls.incrementAndGet(); return Multi.createFrom().nothing(); });
        assertThrows(ObservationDeadlineException.class, () -> stalled.readSource(identity,
                new ReadBounds(65, 131072, 4194304, Duration.ofMillis(5))).await().atMost(Duration.ofSeconds(1)));
        assertEquals(1, calls.get());
        var server = new MongoProjectionRebuildObservationAdapter((kind, filter, options) -> Multi.createFrom().failure(new MongoExecutionTimeoutException(50, "private-message")));
        assertNull(assertThrows(ObservationDeadlineException.class, () -> server.readSource(identity, sourceBounds).await().indefinitely()).getCause());
    }

    @Test void subscriptionsAreLazyFreshAndReturnImmutableLists() {
        var calls = new AtomicInteger();
        var adapter = new MongoProjectionRebuildObservationAdapter((kind, filter, options) -> { calls.incrementAndGet(); return Multi.createFrom().item(raw(source(0))); });
        var pending = adapter.readSource(identity, sourceBounds);
        assertEquals(0, calls.get());
        var first = pending.await().indefinitely();
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
        assertEquals(1, pending.await().indefinitely().size());
        assertEquals(2, calls.get());
    }

    @Test void projectionRetainsCompletePersistedSummaryAndRejectsWrongIdentity() {
        var document = projection();
        var expected = MongoAnalyticsSummaryQueryAdapter.reconstruct(document, projectionIdentity());
        assertEquals(expected, adapter(List.of(raw(document))).readProjection(projectionIdentity(), projectionBounds).await().indefinitely().getFirst().projection());
        document.put("streamId", UUID.randomUUID().toString());
        assertEquals(ReadFailure.PROJECTION_INVALID, assertThrows(ObservationException.class,
                () -> adapter(List.of(raw(document))).readProjection(projectionIdentity(), projectionBounds).await().indefinitely()).failure());
    }

    @Test void productionConstructorIsLazyAndOnlyUsesRawFindOnConfiguredCollections() {
        var operations = new ArrayList<String>();
        ReactiveMongoCollection<RawBsonDocument> collection = proxy(ReactiveMongoCollection.class, (method, args) -> {
            assertEquals("find", method);
            assertInstanceOf(FindOptions.class, args[1]);
            operations.add(method);
            return Multi.createFrom().empty();
        });
        ReactiveMongoDatabase database = proxy(ReactiveMongoDatabase.class, (method, args) -> {
            assertEquals("getCollection", method);
            assertEquals(RawBsonDocument.class, args[1]);
            operations.add((String) args[0]);
            return collection;
        });
        ReactiveMongoClient client = proxy(ReactiveMongoClient.class, (method, args) -> {
            assertEquals("getDatabase", method);
            assertEquals("synthetic-db", args[0]);
            return database;
        });
        @SuppressWarnings("unchecked")
        Instance<ReactiveMongoClient> clients = proxy(Instance.class, (method, args) -> {
            assertEquals("get", method);
            return client;
        });
        var adapter = new MongoProjectionRebuildObservationAdapter(clients, "synthetic-db", "synthetic-inbox", "synthetic-summary");
        assertTrue(operations.isEmpty());
        var pending = adapter.readSource(identity, sourceBounds);
        assertTrue(operations.isEmpty());
        pending.await().indefinitely();
        adapter.readProjection(projectionIdentity(), projectionBounds).await().indefinitely();
        assertEquals(List.of("synthetic-inbox", "find", "synthetic-summary", "find"), operations);
    }

    @Test void synchronousQueryFailuresAndNullStreamsAreSanitized() {
        for (boolean returnsNull : List.of(true, false)) {
            var adapter = new MongoProjectionRebuildObservationAdapter((kind, filter, options) -> {
                if (returnsNull) return null;
                throw new IllegalStateException("private-driver-detail");
            });
            var failure = assertThrows(ObservationException.class, () -> adapter.readSource(identity, sourceBounds).await().indefinitely());
            assertEquals(ReadFailure.UNAVAILABLE, failure.failure());
            assertNull(failure.getCause());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> handler.apply(method.getName(), args));
    }

    private AnalyticsProjectionIdentity projectionIdentity() { return new AnalyticsProjectionIdentity(session, stream, "baseline-v1"); }
    private Document projection() {
        var batch = MongoTelemetryInboxAdapter.reconstruct(source(0));
        var summary = new com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator().calculate(List.of(batch));
        return new Document("therapySessionId", session.toString()).append("streamId", stream.toString())
                .append("calculationVersion", "baseline-v1").append("admittedBatchCount", 1L)
                .append("summary", MongoStreamSummaryProjectionAdapter.summaryDocument(summary));
    }
    private MongoProjectionRebuildObservationAdapter adapter(List<RawBsonDocument> rows) {
        return new MongoProjectionRebuildObservationAdapter((kind, filter, options) -> Multi.createFrom().iterable(rows));
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object[]> options(FindOptions options) {
        var applied = new HashMap<String, Object[]>();
        FindPublisher<RawBsonDocument> publisher = (FindPublisher<RawBsonDocument>) Proxy.newProxyInstance(
                FindPublisher.class.getClassLoader(), new Class<?>[] {FindPublisher.class}, (proxy, method, args) -> {
                    applied.put(method.getName(), args);
                    return proxy;
                });
        options.apply(publisher);
        return applied;
    }

    private Document source(long sequence) {
        var time = Instant.parse("2026-09-18T10:00:00Z").plusSeconds(sequence);
        var batch = new AcceptedTelemetryBatch(1, UUID.randomUUID(), UUID.randomUUID(), session, stream, sequence,
                time, time.plusMillis(1), time.plusMillis(2), UUID.randomUUID(), "trace",
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9)), List.of(), "synthetic-hash");
        return MongoTelemetryInboxAdapter.admittedDocument(batch, TelemetryAdmissionQuality.LATE_ARRIVAL,
                new TelemetryInboxPort.Provenance("synthetic-topic", 0, 0, 128), time);
    }
    private static RawBsonDocument raw(Document document) { return new RawBsonDocument(document, new DocumentCodec()); }
}

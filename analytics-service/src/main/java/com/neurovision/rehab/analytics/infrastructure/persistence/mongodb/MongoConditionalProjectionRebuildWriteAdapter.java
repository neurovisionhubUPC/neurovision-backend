package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ConditionalProjectionRebuildWritePort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import mutiny.zero.flow.adapters.AdaptersToFlow;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.codecs.DocumentCodec;

/**
 * Explicitly constructed internal adapter, not a CDI bean or public maintenance entry point.
 * Rechecks the existing unique identity index, then sends at most one raw write command.
 * Mongo driver 5.6.5 runCommand does not use the retryable insert/update path. Its client
 * withTimeout view is used without an explicit command maxTimeMS (combining them is undefined).
 * One deadline covers index inspection, encoding, dispatch and acknowledgement. Cancellation
 * and timeout do not prove server cancellation or rollback. No indexes are created or repaired.
 * Concurrent administrative index removal is outside this contract; prevent such DDL during apply.
 * Whole-summary BSON equality may conservatively conflict on equivalent reordered legacy BSON.
 * Index metadata policy: at most 64 records, 64 KiB encoded BSON each and 256 KiB per scan.
 * These are logical bounds, not a hard driver-buffer/heap bound. Cursor cancellation is best effort.
 */
public final class MongoConditionalProjectionRebuildWriteAdapter implements ConditionalProjectionRebuildWritePort {
    private static final String INDEX = "uq_analytics_stream_calculation";
    private static final List<String> KEYS = List.of("therapySessionId", "streamId", "calculationVersion");
    private final RawOperations operations;
    private final String collection;
    private final LongSupplier nanoTime;

    public MongoConditionalProjectionRebuildWriteAdapter(Instance<ReactiveMongoClient> clients,
            String database, String collection) {
        this(nativeOperations(clients, database, collection), collection, System::nanoTime);
    }

    MongoConditionalProjectionRebuildWriteAdapter(RawOperations operations, String collection, LongSupplier nanoTime) {
        this.operations = Objects.requireNonNull(operations);
        if (collection == null || collection.isBlank()) throw new IllegalArgumentException("Invalid projection collection");
        this.collection = collection;
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    @Override
    public Uni<WriteResult> writeIfUnchanged(WriteRequest request, Duration timeout) {
        return Uni.createFrom().deferred(() -> {
            Objects.requireNonNull(request);
            if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(2)) > 0) throw unavailable();
            var budget = new Budget(timeout.toNanos());
            var concern = operations.writeConcern();
            if (concern == null || !concern.isAcknowledged()) throw unavailable();
            var indexes = operations.indexes(budget.remaining());
            if (indexes == null) throw unavailable();
            var progress = new IndexProgress();
            // A 65th document is a sentinel, never a truncated successful index inspection.
            return indexes.select().first(65).map(raw -> {
                if (++progress.count > 64 || raw == null) throw unavailable();
                int bytes = raw.getByteBuffer().remaining();
                progress.bytes += bytes;
                if (bytes > 65536 || progress.bytes > 262144) throw unavailable();
                return raw.decode(new DocumentCodec());
            }).collect().asList().ifNoItem().after(budget.remaining()).fail()
                    .chain(evidence -> {
                        budget.remaining();
                        requireIndex(evidence);
                        var command = command(request, concern);
                        var result = operations.write(command, budget.remaining());
                        if (result == null) throw unavailable();
                        return result.ifNoItem().after(budget.remaining()).fail().map(response -> {
                            budget.remaining();
                            return classify(response, request.expected() == null);
                        });
                    });
        }).onFailure().transform(failure -> unavailable());
    }

    private Document command(WriteRequest request, WriteConcern concern) {
        var candidate = request.candidate();
        final Document command;
        if (request.expected() == null) {
            var inserted = identity(candidate.identity())
                    .append("admittedBatchCount", candidate.admittedBatchCount())
                    .append("summary", MongoStreamSummaryProjectionAdapter.summaryDocument(candidate.summary()));
            command = new Document("insert", collection).append("documents", List.of(inserted));
        } else {
            var expected = request.expected();
            var filter = identity(expected.identity()).append("admittedBatchCount", expected.revision())
                    .append("summary", MongoStreamSummaryProjectionAdapter.summaryDocument(expected.summary()));
            var update = new Document("$set", new Document("admittedBatchCount", candidate.admittedBatchCount())
                    .append("summary", MongoStreamSummaryProjectionAdapter.summaryDocument(candidate.summary())));
            var operation = new Document("q", filter).append("u", update).append("multi", false)
                    .append("upsert", false).append("collation", new Document("locale", "simple"));
            command = new Document("update", collection).append("updates", List.of(operation));
        }
        return command.append("ordered", true).append("writeConcern", concern.asDocument());
    }

    private static Document identity(AnalyticsProjectionIdentity identity) {
        return new Document("therapySessionId", identity.therapySessionId().toString())
                .append("streamId", identity.streamId().toString()).append("calculationVersion", identity.calculationVersion());
    }

    private static void requireIndex(List<Document> indexes) {
        int matches = 0;
        for (var index : indexes) {
            if (!INDEX.equals(index.get("name"))) continue;
            var keys = index.get("key", Document.class);
            if (keys == null || !new ArrayList<>(keys.keySet()).equals(KEYS)
                    || keys.values().stream().anyMatch(value -> !number(value, 1))
                    || !Boolean.TRUE.equals(index.get("unique"))
                    || (index.containsKey("sparse") && !Boolean.FALSE.equals(index.get("sparse")))
                    || index.containsKey("partialFilterExpression") || index.containsKey("expireAfterSeconds")
                    || (index.containsKey("collation") && !new Document("locale", "simple").equals(index.get("collation")))) {
                throw unavailable();
            }
            matches++;
        }
        if (matches != 1) throw unavailable();
    }

    private static WriteResult classify(Document response, boolean insert) {
        if (response == null || !number(response.get("ok"), 1)
                || response.containsKey("writeConcernError") || response.containsKey("writeConcernErrors")) throw unavailable();
        if (response.containsKey("upserted") && !List.of().equals(response.get("upserted"))) throw unavailable();
        Object errors = response.get("writeErrors");
        if (response.containsKey("writeErrors") && !(errors instanceof List<?>)) throw unavailable();
        if (errors != null && (!(errors instanceof List<?>) || !((List<?>) errors).isEmpty())) {
            if (insert && number(response.get("n"), 0) && errors instanceof List<?> list && list.size() == 1
                    && list.getFirst() instanceof Document error && number(error.get("index"), 0) && number(error.get("code"), 11000)) {
                return WriteResult.PRECONDITION_FAILED;
            }
            throw unavailable();
        }
        if (insert) {
            if (number(response.get("n"), 1)) return WriteResult.INSERTED;
        } else {
            if (number(response.get("n"), 0) && number(response.get("nModified"), 0)) return WriteResult.PRECONDITION_FAILED;
            if (number(response.get("n"), 1) && number(response.get("nModified"), 1)) return WriteResult.REPLACED;
        }
        throw unavailable();
    }

    private static boolean number(Object value, int expected) {
        return value instanceof Number n && n.doubleValue() == expected;
    }

    private static RawOperations nativeOperations(Instance<ReactiveMongoClient> clients, String database, String collection) {
        Objects.requireNonNull(clients);
        if (database == null || database.isBlank() || collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("Invalid projection location");
        }
        return new RawOperations() {
            private com.mongodb.reactivestreams.client.MongoDatabase database() {
                return clients.get().unwrap().getDatabase(database);
            }
            public WriteConcern writeConcern() { return database().getWriteConcern(); }
            public Multi<RawBsonDocument> indexes(Duration timeout) {
                var publisher = database().getCollection(collection, RawBsonDocument.class)
                        .withReadPreference(ReadPreference.primary()).withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .listIndexes(RawBsonDocument.class).batchSize(8);
                return Multi.createFrom().publisher(AdaptersToFlow.publisher(publisher));
            }
            public Uni<Document> write(Document command, Duration timeout) {
                var publisher = database().withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .runCommand(command, ReadPreference.primary());
                return Uni.createFrom().publisher(AdaptersToFlow.publisher(publisher));
            }
        };
    }

    private final class Budget {
        private final long start = nanoTime.getAsLong();
        private final long allowance;
        Budget(long allowance) { this.allowance = allowance; }
        Duration remaining() {
            long elapsed = nanoTime.getAsLong() - start;
            if (elapsed < 0 || elapsed >= allowance) throw unavailable();
            long millis = TimeUnit.NANOSECONDS.toMillis(allowance - elapsed);
            if (millis < 1) throw unavailable(); // Never convert a fractional millisecond to unlimited zero.
            return Duration.ofMillis(millis);
        }
    }

    interface RawOperations {
        WriteConcern writeConcern();
        Multi<RawBsonDocument> indexes(Duration timeout);
        Uni<Document> write(Document command, Duration timeout);
    }
    private static final class IndexProgress { int count; long bytes; }
    private static RuntimeException unavailable() {
        return new RuntimeException("Conditional projection write unavailable", null, false, false) { };
    }
}

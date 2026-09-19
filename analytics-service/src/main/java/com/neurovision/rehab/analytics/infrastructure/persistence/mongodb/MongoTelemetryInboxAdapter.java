package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.compoundIndex;
import static com.mongodb.client.model.Sorts.descending;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.IndexOptions;
import io.quarkus.mongodb.FindOptions;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AdmittedTelemetryStreamSourcePort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryStream;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryStreamIdentity;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MongoTelemetryInboxAdapter implements TelemetryInboxPort, AdmittedTelemetryStreamSourcePort {
    private final Instance<ReactiveMongoClient> clients;
    private final String database;
    private final String collectionName;
    private final AtomicBoolean prepared = new AtomicBoolean();

    @Inject
    public MongoTelemetryInboxAdapter(Instance<ReactiveMongoClient> clients,
            @ConfigProperty(name = "quarkus.mongodb.database") String database,
            @ConfigProperty(name = "analytics.inbox.collection") String collectionName) {
        this.clients = clients;
        this.database = database;
        this.collectionName = collectionName;
    }

    @Override
    public Uni<Void> prepare() {
        if (prepared.get()) return Uni.createFrom().voidItem();
        var globalBatch = collection().createIndex(ascending("batchId"),
                new IndexOptions().unique(true).name("uq_analytics_batch_id"));
        var streamSequence = collection().createIndex(
                compoundIndex(ascending("therapySessionId"), ascending("streamId"), ascending("sequence")),
                new IndexOptions().unique(true).name("uq_analytics_stream_sequence"));
        return Uni.combine().all().unis(globalBatch, streamSequence).discardItems()
                .invoke(() -> prepared.set(true));
    }

    @Override
    public Uni<Optional<StoredIdentity>> findByBatchId(UUID batchId) {
        return collection().find(eq("batchId", batchId.toString())).collect().first()
                .map(this::identity);
    }

    @Override
    public Uni<Optional<StoredIdentity>> findBySequence(UUID therapySessionId, UUID streamId, long sequence) {
        return collection().find(and(eq("therapySessionId", therapySessionId.toString()),
                        eq("streamId", streamId.toString()), eq("sequence", sequence)))
                .collect().first().map(this::identity);
    }

    @Override
    public Uni<Optional<Long>> maximumSequence(UUID therapySessionId, UUID streamId) {
        var options = new FindOptions().sort(descending("sequence")).limit(1);
        return collection().find(and(eq("therapySessionId", therapySessionId.toString()),
                        eq("streamId", streamId.toString())), options)
                .collect().first()
                .map(document -> document == null ? Optional.empty()
                        : Optional.of(document.getLong("sequence")));
    }

    @Override
    public Uni<Void> insert(AcceptedTelemetryBatch batch, TelemetryAdmissionQuality quality,
            Provenance provenance, Instant admittedAt) {
        var document = admittedDocument(batch, quality, provenance, admittedAt);
        return collection().insertOne(document).replaceWithVoid()
                .onFailure(this::duplicateKey).transform(ignored -> new ConcurrentInsertException());
    }

    static Document admittedDocument(AcceptedTelemetryBatch batch, TelemetryAdmissionQuality quality,
            Provenance provenance, Instant admittedAt) {
        var document = logicalDocument(batch)
                .append("_id", batch.batchId().toString())
                .append("batchId", batch.batchId().toString())
                .append("payloadSha256", batch.payloadSha256())
                .append("status", "ADMITTED")
                .append("admissionQuality", quality.name())
                .append("admittedAt", admittedAt.toString())
                .append("attemptEventId", batch.eventId().toString())
                .append("serverReceivedAt", batch.serverReceivedAt().toString())
                .append("correlationId", batch.correlationId().toString())
                .append("source", new Document("topic", provenance.topic())
                        .append("partition", provenance.partition())
                        .append("offset", provenance.offset())
                        .append("payloadBytes", provenance.payloadBytes()));
        if (batch.traceId() != null) document.append("traceId", batch.traceId());
        return document;
    }

    @Override
    public Uni<AdmittedTelemetryStream> loadCompleteStream(TelemetryStreamIdentity identity) {
        var filter = and(eq("therapySessionId", identity.therapySessionId().toString()),
                eq("streamId", identity.streamId().toString()));
        var options = new FindOptions().sort(ascending("sequence"));
        return collection().find(filter, options).map(MongoTelemetryInboxAdapter::reconstruct).collect().asList()
                .map(batches -> new AdmittedTelemetryStream(identity, batches.size(), batches));
    }

    private Optional<StoredIdentity> identity(Document document) {
        return document == null ? Optional.empty() : Optional.of(new StoredIdentity(
                UUID.fromString(document.getString("batchId")), document.getString("payloadSha256")));
    }

    private static Document logicalDocument(AcceptedTelemetryBatch batch) {
        List<Document> gaze = batch.gazeSamples().stream().map(sample -> new Document("offsetMs", sample.offsetMs())
                .append("x", sample.x()).append("y", sample.y()).append("confidence", sample.confidence())).toList();
        List<Document> gameplay = batch.gameplaySamples().stream().map(sample ->
                new Document("offsetMs", sample.offsetMs()).append("playerX", sample.playerX())
                        .append("playerY", sample.playerY()).append("targetX", sample.targetX())
                        .append("targetY", sample.targetY()).append("score", sample.score())
                        .append("speed", sample.speed()).append("progress", sample.progress())).toList();
        return new Document("schemaVersion", batch.schemaVersion())
                .append("therapySessionId", batch.therapySessionId().toString())
                .append("streamId", batch.streamId().toString())
                .append("sequence", batch.sequence())
                .append("capturedAt", batch.capturedAt().toString())
                .append("sentAt", batch.sentAt().toString())
                .append("gazeSamples", gaze)
                .append("gameplaySamples", gameplay);
    }

    static AdmittedTelemetryBatch reconstruct(Document document) {
        try {
            if (!"ADMITTED".equals(requiredString(document, "status"))) {
                throw new IllegalArgumentException("Inbox row is not admitted");
            }
            var gaze = requiredDocuments(document, "gazeSamples").stream()
                    .map(sample -> new AcceptedTelemetryBatch.GazeSample(
                            requiredLong(sample, "offsetMs"), requiredDouble(sample, "x"),
                            requiredDouble(sample, "y"), requiredDouble(sample, "confidence")))
                    .toList();
            var gameplay = requiredDocuments(document, "gameplaySamples").stream()
                    .map(sample -> new AcceptedTelemetryBatch.GameplaySample(
                            requiredLong(sample, "offsetMs"), requiredDouble(sample, "playerX"),
                            requiredDouble(sample, "playerY"), requiredDouble(sample, "targetX"),
                            requiredDouble(sample, "targetY"), requiredLong(sample, "score"),
                            requiredDouble(sample, "speed"), requiredDouble(sample, "progress")))
                    .toList();
            var batch = new AcceptedTelemetryBatch(
                    requiredInt(document, "schemaVersion"),
                    UUID.fromString(requiredString(document, "attemptEventId")),
                    UUID.fromString(requiredString(document, "batchId")),
                    UUID.fromString(requiredString(document, "therapySessionId")),
                    UUID.fromString(requiredString(document, "streamId")),
                    requiredLong(document, "sequence"),
                    Instant.parse(requiredString(document, "capturedAt")),
                    Instant.parse(requiredString(document, "sentAt")),
                    Instant.parse(requiredString(document, "serverReceivedAt")),
                    UUID.fromString(requiredString(document, "correlationId")),
                    optionalString(document, "traceId"),
                    gaze,
                    gameplay,
                    requiredString(document, "payloadSha256"));
            return new AdmittedTelemetryBatch(batch,
                    TelemetryAdmissionQuality.valueOf(requiredString(document, "admissionQuality")));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid admitted inbox document", invalid);
        }
    }

    private static List<Document> requiredDocuments(Document document, String field) {
        var values = document.getList(field, Document.class);
        if (values == null || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Missing document list: " + field);
        }
        return values;
    }

    private static String requiredString(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Missing string: " + field);
        }
        return text;
    }

    private static String optionalString(Document document, String field) {
        Object value = document.get(field);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException("Invalid string: " + field);
        return text;
    }

    private static int requiredInt(Document document, String field) {
        long value = requiredLong(document, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Integer out of range: " + field);
        }
        return (int) value;
    }

    private static long requiredLong(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Missing number: " + field);
        long converted = number.longValue();
        if (!Double.isFinite(number.doubleValue()) || number.doubleValue() != converted) {
            throw new IllegalArgumentException("Invalid integer: " + field);
        }
        return converted;
    }

    private static double requiredDouble(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Missing finite number: " + field);
        }
        return number.doubleValue();
    }

    private boolean duplicateKey(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoWriteException write && write.getCode() == 11000) return true;
            if (cause instanceof MongoCommandException command && command.getErrorCode() == 11000) return true;
        }
        return false;
    }

    private ReactiveMongoCollection<Document> collection() {
        return clients.get().getDatabase(database).getCollection(collectionName);
    }
}

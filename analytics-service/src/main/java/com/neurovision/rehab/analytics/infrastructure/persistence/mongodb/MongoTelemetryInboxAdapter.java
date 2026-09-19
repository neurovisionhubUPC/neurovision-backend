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
import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
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
public class MongoTelemetryInboxAdapter implements TelemetryInboxPort {
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
    public Uni<Void> insert(AcceptedTelemetryBatch batch, AdmissionQuality quality,
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
                .append("source", new Document("topic", provenance.topic())
                        .append("partition", provenance.partition())
                        .append("offset", provenance.offset())
                        .append("payloadBytes", provenance.payloadBytes()));
        return collection().insertOne(document).replaceWithVoid()
                .onFailure(this::duplicateKey).transform(ignored -> new ConcurrentInsertException());
    }

    private Optional<StoredIdentity> identity(Document document) {
        return document == null ? Optional.empty() : Optional.of(new StoredIdentity(
                UUID.fromString(document.getString("batchId")), document.getString("payloadSha256")));
    }

    private Document logicalDocument(AcceptedTelemetryBatch batch) {
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

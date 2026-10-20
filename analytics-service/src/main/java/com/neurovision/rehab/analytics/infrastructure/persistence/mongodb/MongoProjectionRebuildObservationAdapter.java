package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Projections.*;
import static com.mongodb.client.model.Sorts.ascending;

import com.mongodb.MongoExecutionTimeoutException;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ProjectionRebuildObservationPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.codecs.DocumentCodec;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Read-only, lazy Mongo implementation of the internal optimistic observation port.
 * Fetches only reconstruction fields as raw BSON, checks encoded projected bytes before decoding,
 * and reuses existing lossless mappers. No indexes, writes, counts, retries, or client creation.
 * Limits are logical: raw driver buffers can exist before rejection. Server maxTime and client
 * timeout do not guarantee cancellation or a snapshot across the four application reads.
 */
@ApplicationScoped
public class MongoProjectionRebuildObservationAdapter implements ProjectionRebuildObservationPort {
    private static final Bson SOURCE_FIELDS = fields(excludeId(), include("schemaVersion", "attemptEventId", "batchId",
            "therapySessionId", "streamId", "sequence", "capturedAt", "sentAt", "serverReceivedAt", "correlationId",
            "traceId", "gazeSamples", "gameplaySamples", "payloadSha256", "admissionQuality", "status", "inboxSchemaVersion"));
    private static final Bson PROJECTION_FIELDS = fields(excludeId(), include("therapySessionId", "streamId",
            "calculationVersion", "admittedBatchCount", "summary"));
    private final RawQuery query;

    @Inject
    public MongoProjectionRebuildObservationAdapter(Instance<ReactiveMongoClient> clients,
            @ConfigProperty(name = "quarkus.mongodb.database") String database,
            @ConfigProperty(name = "analytics.inbox.collection") String inbox,
            @ConfigProperty(name = "analytics.summary.collection") String summaries) {
        this((kind, filter, options) -> clients.get().getDatabase(database)
                .getCollection(kind == CollectionKind.SOURCE ? inbox : summaries, RawBsonDocument.class)
                .find(filter, options));
    }

    MongoProjectionRebuildObservationAdapter(RawQuery query) { this.query = Objects.requireNonNull(query); }

    @Override
    public Uni<List<SourceRow>> readSource(TelemetryStreamIdentity identity, ReadBounds bounds) {
        return observe(CollectionKind.SOURCE, bounds, () -> {
            Objects.requireNonNull(identity);
            return and(eq("therapySessionId", identity.therapySessionId().toString()), eq("streamId", identity.streamId().toString()));
        }, document -> {
            var admitted = MongoTelemetryInboxAdapter.reconstruct(document);
            new AdmittedTelemetryStream(identity, 1, List.of(admitted));
            // Missing legacy schema is distinct from an explicit null or unsupported schema.
            Integer schema = document.containsKey("inboxSchemaVersion")
                    ? ((Number) document.get("inboxSchemaVersion")).intValue() : null;
            return new SourceRow(admitted, schema, document.getString("status"), 1);
        }, row -> new SourceRow(row.value().admitted(), row.value().inboxSchemaVersion(), row.value().status(), row.bytes()));
    }

    @Override
    public Uni<List<ProjectionRow>> readProjection(AnalyticsProjectionIdentity identity, ReadBounds bounds) {
        return observe(CollectionKind.PROJECTION, bounds, () -> {
            Objects.requireNonNull(identity);
            if (!"baseline-v1".equals(identity.calculationVersion())) throw new IllegalArgumentException();
            return MongoAnalyticsSummaryQueryAdapter.identityFilter(identity);
        }, document -> new ProjectionRow(MongoAnalyticsSummaryQueryAdapter.reconstruct(document, identity), 1),
                row -> new ProjectionRow(row.value().projection(), row.bytes()));
    }

    private <T, R> Uni<List<R>> observe(CollectionKind kind, ReadBounds bounds,
            java.util.function.Supplier<Bson> filter, Function<Document, T> mapper, Function<Sized<T>, R> sizedMapper) {
        return Uni.createFrom().deferred(() -> {
            validateBounds(kind, bounds);
            final Bson exactFilter;
            try { exactFilter = filter.get(); }
            catch (RuntimeException invalid) { throw invalid(kind); }
            // Floor, never round up beyond the remaining allowance or send zero (unlimited).
            long millis = bounds.queryTimeout().toMillis();
            if (millis < 1) throw new ObservationDeadlineException();
            var options = new FindOptions().limit(bounds.maxDocuments()).batchSize(Math.min(8, bounds.maxDocuments()))
                    .maxTime(millis, TimeUnit.MILLISECONDS).projection(kind == CollectionKind.SOURCE ? SOURCE_FIELDS : PROJECTION_FIELDS);
            if (kind == CollectionKind.SOURCE) options.sort(ascending("sequence", "batchId"));
            var rows = query.find(kind, exactFilter, options);
            if (rows == null) throw new ObservationException(ReadFailure.UNAVAILABLE);
            var progress = new Progress();
            return rows.select().first(bounds.maxDocuments()).onItem().transform(raw -> {
                if (++progress.documents == bounds.maxDocuments()) {
                    if (kind == CollectionKind.SOURCE) throw new ObservationLimitException(ObservationLimit.SOURCE_BATCHES);
                    throw invalid(kind);
                }
                if (raw == null) throw invalid(kind);
                int bytes = raw.getByteBuffer().remaining();
                if (bytes > bounds.maxDocumentBytes()) throw new ObservationLimitException(kind == CollectionKind.SOURCE
                        ? ObservationLimit.SOURCE_DOCUMENT_BYTES : ObservationLimit.PROJECTION_DOCUMENT_BYTES);
                progress.bytes += bytes;
                if (progress.bytes > bounds.maxTotalBytes()) throw new ObservationLimitException(kind == CollectionKind.SOURCE
                        ? ObservationLimit.SOURCE_PASS_BYTES : ObservationLimit.PROJECTION_DOCUMENT_BYTES);
                final T mapped;
                try {
                    var document = raw.decode(new DocumentCodec());
                    if (kind == CollectionKind.SOURCE) {
                        var gaze = document.get("gazeSamples");
                        var gameplay = document.get("gameplaySamples");
                        if (!(gaze instanceof List<?> g) || !(gameplay instanceof List<?> p)) throw invalid(kind);
                        long count = (long) g.size() + p.size();
                        if (count > 1000) throw new ObservationLimitException(ObservationLimit.BATCH_SAMPLES);
                        progress.samples += count;
                        if (progress.samples > 64000) throw new ObservationLimitException(ObservationLimit.TOTAL_SAMPLES);
                    }
                    mapped = mapper.apply(document);
                } catch (ObservationLimitException exceeded) { throw exceeded; }
                catch (RuntimeException malformed) { throw invalid(kind); }
                return sizedMapper.apply(new Sized<>(mapped, bytes));
            }).collect().asList().map(List::copyOf)
                    .ifNoItem().after(bounds.queryTimeout()).failWith(ObservationDeadlineException::new);
        }).onFailure().transform(MongoProjectionRebuildObservationAdapter::safeFailure);
    }

    private static void validateBounds(CollectionKind kind, ReadBounds bounds) {
        if (bounds == null) throw new ObservationException(ReadFailure.UNAVAILABLE);
        boolean source = kind == CollectionKind.SOURCE;
        // Smaller sentinels could falsely certify an incomplete source as complete.
        if (bounds.maxDocuments() != (source ? 65 : 2)
                || bounds.maxDocumentBytes() > (source ? 131072 : 262144)
                || bounds.maxTotalBytes() > (source ? 4194304 : 524288)) {
            throw new ObservationException(ReadFailure.UNAVAILABLE);
        }
    }

    private static ObservationException invalid(CollectionKind kind) {
        return new ObservationException(kind == CollectionKind.SOURCE ? ReadFailure.SOURCE_INVALID : ReadFailure.PROJECTION_INVALID);
    }

    private static Throwable safeFailure(Throwable failure) {
        if (failure instanceof ObservationException || failure instanceof ObservationLimitException
                || failure instanceof ObservationDeadlineException) return failure;
        if (failure instanceof MongoExecutionTimeoutException) return new ObservationDeadlineException();
        return new ObservationException(ReadFailure.UNAVAILABLE);
    }

    enum CollectionKind { SOURCE, PROJECTION }
    @FunctionalInterface interface RawQuery { Multi<RawBsonDocument> find(CollectionKind kind, Bson filter, FindOptions options); }
    private record Sized<T>(T value, int bytes) { }
    private static final class Progress { int documents; long bytes; long samples; }
}

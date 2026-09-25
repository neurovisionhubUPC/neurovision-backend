package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Sorts.ascending;

import com.neurovision.rehab.analytics.application.ports.out.persistence.ReplayPageQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ReplayPageQueryPort.Found;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ReplayPageQueryPort.NotFound;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayCursor;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayCursorRejectedException;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayLane;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayLanePage;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayMetadata;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayPage;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayProjectionInconsistentException;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplaySourceBatch;
import com.neurovision.rehab.analytics.domain.services.ReplayPageAssembler;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Reads separate bounded batch windows for the independent replay lanes. */
@ApplicationScoped
public class MongoReplayPageQueryAdapter implements ReplayPageQueryPort {
    private final Instance<ReactiveMongoClient> clients;
    private final String database;
    private final String inboxCollectionName;
    private final String summaryCollectionName;
    private final int maximumSourceSamples;

    @Inject
    public MongoReplayPageQueryAdapter(Instance<ReactiveMongoClient> clients,
            @ConfigProperty(name = "quarkus.mongodb.database") String database,
            @ConfigProperty(name = "analytics.inbox.collection") String inboxCollectionName,
            @ConfigProperty(name = "analytics.summary.collection") String summaryCollectionName,
            @ConfigProperty(name = "analytics.consumer.max-samples") int maximumSourceSamples) {
        if (maximumSourceSamples < 1) throw new IllegalArgumentException("Invalid analytics source sample bound");
        this.clients = clients;
        this.database = database;
        this.inboxCollectionName = inboxCollectionName;
        this.summaryCollectionName = summaryCollectionName;
        this.maximumSourceSamples = maximumSourceSamples;
    }

    @Override
    public Uni<Result> loadPage(AnalyticsProjectionIdentity identity, ReplayCursor gazeCursor,
            ReplayCursor gameplayCursor, int pageSize) {
        Objects.requireNonNull(identity);
        if (pageSize < 1 || pageSize > ReplayPageAssembler.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid replay page size");
        }
        return summaries().find(summaryFilter(identity)).collect().first().chain(document -> {
            if (document == null) return Uni.createFrom().item(NotFound.INSTANCE);
            ReplayMetadata metadata = metadata(document, identity);
            validateCursor(metadata, ReplayLane.GAZE, gazeCursor);
            validateCursor(metadata, ReplayLane.GAMEPLAY, gameplayCursor);
            return requireInboxRevision(metadata, gazeCursor, gameplayCursor).chain(ignored -> {
                boolean cursorBoundRequest = gazeCursor != null || gameplayCursor != null;
                Uni<ReplayLanePage> gaze = loadLane(metadata, ReplayLane.GAZE, gazeCursor, pageSize,
                        cursorBoundRequest);
                Uni<ReplayLanePage> gameplay = loadLane(metadata, ReplayLane.GAMEPLAY, gameplayCursor, pageSize,
                        cursorBoundRequest);
                return Uni.combine().all().unis(gaze, gameplay).asTuple()
                        .map(tuple -> new ReplayPage(metadata, tuple.getItem1(), tuple.getItem2()))
                        .chain(page -> Uni.combine().all().unis(
                                summaries().find(summaryFilter(identity)).collect().first(), inboxCount(identity))
                                .asTuple().map(tuple -> {
                                    Document after = tuple.getItem1();
                                    long afterCount = tuple.getItem2();
                                    if (after == null
                                            || metadata(after, identity).projectionRevision() != metadata.projectionRevision()
                                            || afterCount != metadata.projectionRevision()) {
                                        throw inconsistent(gazeCursor, gameplayCursor);
                                    }
                                    return new Found(page);
                                }));
            });
        });
    }

    private Uni<Void> requireInboxRevision(ReplayMetadata metadata, ReplayCursor gazeCursor,
            ReplayCursor gameplayCursor) {
        return inboxCount(metadata.identity()).map(count -> {
            if (count != metadata.projectionRevision()) throw inconsistent(gazeCursor, gameplayCursor);
            return null;
        });
    }

    private Uni<Long> inboxCount(AnalyticsProjectionIdentity identity) {
        return inbox().countDocuments(and(eq("therapySessionId", identity.therapySessionId().toString()),
                eq("streamId", identity.streamId().toString()), eq("status", "ADMITTED")));
    }

    private static RuntimeException inconsistent(ReplayCursor gazeCursor, ReplayCursor gameplayCursor) {
        return gazeCursor != null || gameplayCursor != null
                ? new ReplayCursorRejectedException() : new ReplayProjectionInconsistentException();
    }

    private Uni<ReplayLanePage> loadLane(ReplayMetadata metadata, ReplayLane lane, ReplayCursor cursor, int pageSize,
            boolean cursorBoundRequest) {
        return inbox().find(inboxFilter(metadata, cursor), inboxOptions(lane))
                .map(document -> reconstruct(document, maximumSourceSamples, lane))
                .collect().asList()
                .map(batches -> ReplayPageAssembler.page(metadata, lane, batches, cursor, pageSize))
                .onFailure(ReplayProjectionInconsistentException.class)
                .transform(failure -> normalizeProjectionInconsistency(failure, cursorBoundRequest));
    }

    static RuntimeException normalizeProjectionInconsistency(Throwable failure, boolean cursorBoundRequest) {
        return cursorBoundRequest ? new ReplayCursorRejectedException() : (RuntimeException) failure;
    }

    static Bson summaryFilter(AnalyticsProjectionIdentity identity) {
        return and(eq("therapySessionId", identity.therapySessionId().toString()),
                eq("streamId", identity.streamId().toString()), eq("calculationVersion", identity.calculationVersion()));
    }

    static Bson inboxFilter(ReplayMetadata metadata, ReplayCursor cursor) {
        var start = cursor == null ? gte("sequence", metadata.minimumSequence())
                : cursor.resumesWithinBatch() ? gte("sequence", cursor.resumeSequence()) : gt("sequence", cursor.resumeSequence());
        return and(eq("therapySessionId", metadata.identity().therapySessionId().toString()),
                eq("streamId", metadata.identity().streamId().toString()), eq("status", "ADMITTED"),
                start, lte("sequence", metadata.maximumSequence()));
    }

    static FindOptions inboxOptions(ReplayLane lane) {
        return new FindOptions().sort(ascending("sequence")).limit(ReplayPageAssembler.MAX_BATCH_SCAN)
                .projection(inboxProjection(lane));
    }

    static Bson inboxProjection(ReplayLane lane) {
        return include("schemaVersion", "batchId", "therapySessionId", "streamId", "sequence", "capturedAt",
                lane == ReplayLane.GAZE ? "gazeSamples" : "gameplaySamples");
    }

    static ReplayMetadata metadata(Document document, AnalyticsProjectionIdentity requestedIdentity) {
        try {
            UUID sessionId = UUID.fromString(requiredString(document, "therapySessionId"));
            UUID streamId = UUID.fromString(requiredString(document, "streamId"));
            String profile = requiredString(document, "calculationVersion");
            var identity = new AnalyticsProjectionIdentity(sessionId, streamId, profile);
            if (!requestedIdentity.equals(identity)) throw new IllegalArgumentException("Replay summary identity mismatch");
            Document summary = requiredDocument(document, "summary");
            if (!profile.equals(requiredString(summary, "profile")) || !sessionId.toString().equals(requiredString(summary, "therapySessionId"))
                    || !streamId.toString().equals(requiredString(summary, "streamId"))) throw new IllegalArgumentException("Replay summary identity mismatch");
            var gaps = requiredDocuments(summary, "missingSequenceRanges").stream()
                    .map(range -> new HeatmapProjection.SequenceRange(requiredLong(range, "fromInclusive"), requiredLong(range, "toInclusive"))).toList();
            return new ReplayMetadata(identity, requiredLong(document, "admittedBatchCount"),
                    requiredInt(summary, "sourceSchemaVersion"), requiredLong(summary, "minimumSequence"),
                    requiredLong(summary, "maximumSequence"), gaps, requiredBoolean(summary, "unknownSessionBoundary"));
        } catch (RuntimeException invalid) {
            throw new MalformedGazePlotProjectionException("Invalid persisted replay summary", invalid);
        }
    }

    static ReplaySourceBatch reconstruct(Document document, int maximumSourceSamples, ReplayLane lane) {
        try {
            if (maximumSourceSamples < 1) throw new IllegalArgumentException("Invalid replay source sample bound");
            int schemaVersion = requiredInt(document, "schemaVersion");
            UUID batchId = UUID.fromString(requiredString(document, "batchId"));
            UUID sessionId = UUID.fromString(requiredString(document, "therapySessionId"));
            UUID streamId = UUID.fromString(requiredString(document, "streamId"));
            long sequence = requiredLong(document, "sequence");
            java.time.Instant capturedAt = java.time.Instant.parse(requiredString(document, "capturedAt"));
            String field = lane == ReplayLane.GAZE ? "gazeSamples" : "gameplaySamples";
            var samples = requiredDocuments(document, field);
            if (samples.size() > maximumSourceSamples) throw new IllegalArgumentException("Replay lane exceeds admitted sample bound");
            var gaze = lane == ReplayLane.GAZE ? samples.stream().map(sample -> new ReplaySourceBatch.GazeSample(
                    requiredLong(sample, "offsetMs"), requiredDouble(sample, "x"), requiredDouble(sample, "y"),
                    requiredDouble(sample, "confidence"))).toList() : List.<ReplaySourceBatch.GazeSample>of();
            var gameplay = lane == ReplayLane.GAMEPLAY ? samples.stream().map(sample -> new ReplaySourceBatch.GameplaySample(
                    requiredLong(sample, "offsetMs"), requiredDouble(sample, "playerX"), requiredDouble(sample, "playerY"),
                    requiredDouble(sample, "targetX"), requiredDouble(sample, "targetY"), requiredLong(sample, "score"),
                    requiredDouble(sample, "speed"), requiredDouble(sample, "progress"))).toList()
                    : List.<ReplaySourceBatch.GameplaySample>of();
            return new ReplaySourceBatch(schemaVersion, batchId, sessionId, streamId, sequence, capturedAt, gaze, gameplay);
        } catch (RuntimeException invalid) {
            throw new MalformedGazePlotProjectionException("Invalid admitted replay inbox document", invalid);
        }
    }

    private static void validateCursor(ReplayMetadata metadata, ReplayLane lane, ReplayCursor cursor) {
        if (cursor != null && (!metadata.identity().equals(cursor.identity()) || cursor.lane() != lane
                || cursor.projectionRevision() != metadata.projectionRevision()
                || cursor.resumeSequence() > metadata.maximumSequence()
                || cursor.previousSampleSequence() > metadata.maximumSequence())) {
            throw new ReplayCursorRejectedException();
        }
    }

    private static List<Document> requiredDocuments(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("Missing document list: " + field);
        var result = new ArrayList<Document>(values.size());
        for (Object item : values) {
            if (!(item instanceof Document nested)) throw new IllegalArgumentException("Invalid document list: " + field);
            result.add(nested);
        }
        return List.copyOf(result);
    }
    private static Document requiredDocument(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Document nested)) throw new IllegalArgumentException("Missing document: " + field);
        return nested;
    }
    private static String requiredString(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Missing string: " + field);
        return text;
    }
    private static boolean requiredBoolean(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("Missing boolean: " + field);
        return flag;
    }
    private static int requiredInt(Document document, String field) {
        long value = requiredLong(document, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid integer: " + field);
        return (int) value;
    }
    private static long requiredLong(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Missing number: " + field);
        long converted = number.longValue();
        if (!Double.isFinite(number.doubleValue()) || number.doubleValue() != converted) throw new IllegalArgumentException("Invalid integer: " + field);
        return converted;
    }
    private static double requiredDouble(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Missing finite number: " + field);
        }
        return number.doubleValue();
    }
    private ReactiveMongoCollection<Document> inbox() { return clients.get().getDatabase(database).getCollection(inboxCollectionName); }
    private ReactiveMongoCollection<Document> summaries() { return clients.get().getDatabase(database).getCollection(summaryCollectionName); }
}

package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Sorts.ascending;

import io.quarkus.mongodb.FindOptions;
import com.neurovision.rehab.analytics.application.ports.out.persistence.GazePlotPageQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.GazePlotPageQueryPort.Found;
import com.neurovision.rehab.analytics.application.ports.out.persistence.GazePlotPageQueryPort.NotFound;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotCursor;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotMetadata;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotPage;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazeSourceBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection;
import com.neurovision.rehab.analytics.domain.services.GazePlotPageAssembler;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;

/** Reads only a bounded, sequence-ordered window from the admitted inbox. */
@ApplicationScoped
public class MongoGazePlotPageQueryAdapter implements GazePlotPageQueryPort {
    private static final int MAX_BATCH_SCAN = GazePlotPageAssembler.MAX_BATCH_SCAN;
    private final Instance<ReactiveMongoClient> clients;
    private final String database;
    private final String inboxCollectionName;
    private final String summaryCollectionName;
    private final int maximumSourceSamples;

    @Inject
    public MongoGazePlotPageQueryAdapter(Instance<ReactiveMongoClient> clients,
            @ConfigProperty(name = "quarkus.mongodb.database") String database,
            @ConfigProperty(name = "analytics.inbox.collection") String inboxCollectionName,
            @ConfigProperty(name = "analytics.summary.collection") String summaryCollectionName,
            @ConfigProperty(name = "analytics.consumer.max-samples") int maximumSourceSamples) {
        if (maximumSourceSamples < 1) throw new IllegalArgumentException("Invalid analytics sample bound");
        this.clients = clients;
        this.database = database;
        this.inboxCollectionName = inboxCollectionName;
        this.summaryCollectionName = summaryCollectionName;
        this.maximumSourceSamples = maximumSourceSamples;
    }

    @Override
    public Uni<Result> loadPage(AnalyticsProjectionIdentity identity, GazePlotCursor cursor, int pageSize) {
        Objects.requireNonNull(identity, "identity");
        if (pageSize < 1 || pageSize > GazePlotPageAssembler.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid gaze-plot page size");
        }
        return summaries().find(summaryIdentityFilter(identity)).collect().first().chain(document -> {
            if (document == null) return Uni.createFrom().item(NotFound.INSTANCE);
            GazePlotMetadata metadata = reconstructMetadata(document, identity);
            validateCursor(metadata, cursor);
            return inbox().find(inboxPageFilter(identity, metadata, cursor), inboxFindOptions())
                    .map(inboxDocument -> reconstructBatch(inboxDocument, maximumSourceSamples))
                    .collect().asList()
                    .map(batches -> GazePlotPageAssembler.page(metadata, batches, cursor, pageSize))
                    .chain(page -> summaries().find(summaryIdentityFilter(identity)).collect().first()
                            .map(after -> {
                                if (after == null || reconstructMetadata(after, identity).projectionRevision()
                                        != metadata.projectionRevision()) {
                                    throw new IllegalArgumentException("Gaze projection changed during page read");
                                }
                                return new Found(page);
                            }));
        });
    }

    static Bson summaryIdentityFilter(AnalyticsProjectionIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return and(eq("therapySessionId", identity.therapySessionId().toString()),
                eq("streamId", identity.streamId().toString()),
                eq("calculationVersion", identity.calculationVersion()));
    }

    static Bson inboxPageFilter(AnalyticsProjectionIdentity identity, GazePlotMetadata metadata,
            GazePlotCursor cursor) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(metadata, "metadata");
        if (cursor != null) validateCursor(metadata, cursor);
        var sequenceStart = cursor == null
                ? gte("sequence", metadata.minimumSequence())
                : cursor.resumesWithinBatch()
                        ? gte("sequence", cursor.resumeSequence())
                        : gt("sequence", cursor.resumeSequence());
        return and(eq("therapySessionId", identity.therapySessionId().toString()),
                eq("streamId", identity.streamId().toString()), eq("status", "ADMITTED"),
                sequenceStart, lte("sequence", metadata.maximumSequence()));
    }

    static FindOptions inboxFindOptions() {
        return new FindOptions().sort(ascending("sequence")).limit(MAX_BATCH_SCAN)
                .projection(include("schemaVersion", "therapySessionId", "streamId", "sequence",
                        "capturedAt", "gazeSamples", "status"));
    }

    static GazePlotMetadata reconstructMetadata(Document document, AnalyticsProjectionIdentity requestedIdentity) {
        try {
            var identity = new AnalyticsProjectionIdentity(
                    UUID.fromString(requiredString(document, "therapySessionId")),
                    UUID.fromString(requiredString(document, "streamId")),
                    requiredString(document, "calculationVersion"));
            if (!requestedIdentity.equals(identity)
                    || !AnalyticsCalculationProfile.BASELINE_V1.calculationVersion()
                            .equals(identity.calculationVersion())) {
                throw new IllegalArgumentException("Summary identity does not match gaze-plot query");
            }
            long revision = requiredLong(document, "admittedBatchCount");
            Document summary = requiredDocument(document, "summary");
            requireEqual(requiredString(summary, "profile"), identity.calculationVersion());
            requireEqual(requiredString(summary, "therapySessionId"), identity.therapySessionId().toString());
            requireEqual(requiredString(summary, "streamId"), identity.streamId().toString());
            var sampleCounts = requiredDocument(summary, "sampleCounts");
            long gazeCount = requiredLong(sampleCounts, "gaze");
            long gameplayCount = requiredLong(sampleCounts, "gameplay");
            long totalCount = requiredLong(sampleCounts, "total");
            if (Math.addExact(gazeCount, gameplayCount) != totalCount) {
                throw new IllegalArgumentException("Summary sample counts are inconsistent");
            }
            return new GazePlotMetadata(identity, revision, requiredInt(summary, "sourceSchemaVersion"),
                    requiredLong(summary, "minimumSequence"), requiredLong(summary, "maximumSequence"),
                    requiredLong(summary, "highSequence"), requiredSequenceRanges(summary),
                    requiredLong(summary, "lateArrivalCount"), requiredBoolean(summary, "unknownSessionBoundary"),
                    gazeCount, observedTimeRange(summary));
        } catch (RuntimeException invalid) {
            throw new MalformedGazePlotProjectionException("Invalid persisted gaze-plot summary", invalid);
        }
    }

    static GazeSourceBatch reconstructBatch(Document document, int maximumSourceSamples) {
        try {
            if (maximumSourceSamples < 1) throw new IllegalArgumentException("Invalid source sample bound");
            if (!"ADMITTED".equals(requiredString(document, "status"))) {
                throw new IllegalArgumentException("Inbox row is not admitted");
            }
            var sourceSamples = requiredDocuments(document, "gazeSamples");
            if (sourceSamples.size() > maximumSourceSamples) {
                throw new IllegalArgumentException("Gaze source batch exceeds the admitted sample bound");
            }
            var samples = sourceSamples.stream()
                    .map(sample -> new GazeSourceBatch.Sample(requiredLong(sample, "offsetMs"),
                            requiredDouble(sample, "x"), requiredDouble(sample, "y"),
                            requiredDouble(sample, "confidence")))
                    .toList();
            return new GazeSourceBatch(requiredInt(document, "schemaVersion"),
                    UUID.fromString(requiredString(document, "therapySessionId")),
                    UUID.fromString(requiredString(document, "streamId")), requiredLong(document, "sequence"),
                    Instant.parse(requiredString(document, "capturedAt")), samples);
        } catch (RuntimeException invalid) {
            throw new MalformedGazePlotProjectionException("Invalid admitted gaze inbox document", invalid);
        }
    }

    private static List<HeatmapProjection.SequenceRange> requiredSequenceRanges(Document summary) {
        return requiredDocuments(summary, "missingSequenceRanges").stream()
                .map(range -> new HeatmapProjection.SequenceRange(
                        requiredLong(range, "fromInclusive"), requiredLong(range, "toInclusive")))
                .toList();
    }

    private static Optional<GazePlotMetadata.TimeRange> observedTimeRange(Document summary) {
        if (!summary.containsKey("observedTimeRange")) {
            throw new IllegalArgumentException("Missing observed time range");
        }
        Object value = summary.get("observedTimeRange");
        if (value == null) return Optional.empty();
        if (!(value instanceof Document range)) throw new IllegalArgumentException("Invalid observed time range");
        return Optional.of(new GazePlotMetadata.TimeRange(
                Instant.parse(requiredString(range, "minimum")), Instant.parse(requiredString(range, "maximum"))));
    }

    private static void validateCursor(GazePlotMetadata metadata, GazePlotCursor cursor) {
        if (cursor != null && (!metadata.identity().equals(cursor.identity())
                || metadata.projectionRevision() != cursor.projectionRevision()
                || cursor.resumeSequence() > metadata.maximumSequence()
                || cursor.previousPointSequence() > metadata.maximumSequence())) {
            throw new IllegalArgumentException("Stale or mismatched gaze-plot cursor");
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

    private static void requireEqual(String actual, String expected) {
        if (!actual.equals(expected)) throw new IllegalArgumentException("Summary identity mismatch");
    }

    private ReactiveMongoCollection<Document> inbox() {
        return clients.get().getDatabase(database).getCollection(inboxCollectionName);
    }

    private ReactiveMongoCollection<Document> summaries() {
        return clients.get().getDatabase(database).getCollection(summaryCollectionName);
    }

}

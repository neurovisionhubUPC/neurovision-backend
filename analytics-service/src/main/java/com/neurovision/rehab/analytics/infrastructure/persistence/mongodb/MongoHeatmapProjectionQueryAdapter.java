package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

import com.neurovision.rehab.analytics.application.ports.out.persistence.HeatmapProjectionQueryPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection.CellWeight;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection.CoordinateSpace;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
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

@ApplicationScoped
public class MongoHeatmapProjectionQueryAdapter implements HeatmapProjectionQueryPort {
    private final Instance<ReactiveMongoClient> clients;
    private final String database;
    private final String collectionName;

    @Inject
    public MongoHeatmapProjectionQueryAdapter(
            Instance<ReactiveMongoClient> clients,
            @ConfigProperty(name = "quarkus.mongodb.database") String database,
            @ConfigProperty(name = "analytics.summary.collection") String collectionName) {
        this.clients = clients;
        this.database = database;
        this.collectionName = collectionName;
    }

    @Override
    public Uni<Result> load(AnalyticsProjectionIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return query(identity, () -> collection().find(identityFilter(identity)).collect().first());
    }

    static Uni<Result> query(AnalyticsProjectionIdentity identity, DocumentLookup lookup) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(lookup, "lookup");
        return lookup.execute().map(document -> {
            if (document == null) return NotFound.INSTANCE;
            try {
                return new Found(reconstruct(document, identity));
            } catch (RuntimeException invalid) {
                throw new MalformedProjectionException("Invalid persisted heatmap projection", invalid);
            }
        });
    }

    static Bson identityFilter(AnalyticsProjectionIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return and(
                eq("therapySessionId", identity.therapySessionId().toString()),
                eq("streamId", identity.streamId().toString()),
                eq("calculationVersion", identity.calculationVersion()));
    }

    static HeatmapProjection reconstruct(Document document, AnalyticsProjectionIdentity requestedIdentity) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(requestedIdentity, "requestedIdentity");

        var persistedIdentity = new AnalyticsProjectionIdentity(
                UUID.fromString(requiredString(document, "therapySessionId")),
                UUID.fromString(requiredString(document, "streamId")),
                requiredString(document, "calculationVersion"));
        if (!requestedIdentity.equals(persistedIdentity)) {
            throw new IllegalArgumentException("Persisted projection identity does not match query");
        }
        if (!AnalyticsCalculationProfile.BASELINE_V1.calculationVersion()
                .equals(persistedIdentity.calculationVersion())) {
            throw new IllegalArgumentException("Unsupported calculation version");
        }

        Document summary = requiredDocument(document, "summary");
        requireEqual(requiredString(summary, "profile"), persistedIdentity.calculationVersion(),
                "Summary profile does not match projection identity");
        requireEqual(requiredString(summary, "therapySessionId"),
                persistedIdentity.therapySessionId().toString(), "Summary therapy session does not match identity");
        requireEqual(requiredString(summary, "streamId"), persistedIdentity.streamId().toString(),
                "Summary stream does not match identity");

        Document sampleCounts = requiredDocument(summary, "sampleCounts");
        long gaze = requiredLong(sampleCounts, "gaze");
        long gameplay = requiredLong(sampleCounts, "gameplay");
        long total = requiredLong(sampleCounts, "total");
        if (Math.addExact(gaze, gameplay) != total) {
            throw new IllegalArgumentException("Persisted sample counts are inconsistent");
        }
        Optional<HeatmapProjection.TimeRange> observedTimeRange = observedTimeRange(summary);
        if ((total == 0) != observedTimeRange.isEmpty()) {
            throw new IllegalArgumentException("Observed time range does not match persisted sample presence");
        }

        Document heatmap = requiredDocument(summary, "heatmap");
        List<Long> cellCounts = requiredLongList(heatmap, "cellCounts");
        List<HeatmapProjection.SequenceRange> missingRanges = requiredDocuments(summary, "missingSequenceRanges")
                .stream()
                .map(range -> new HeatmapProjection.SequenceRange(
                        requiredLong(range, "fromInclusive"), requiredLong(range, "toInclusive")))
                .toList();

        return new HeatmapProjection(
                persistedIdentity,
                requiredLong(document, "admittedBatchCount"),
                requiredInt(summary, "sourceSchemaVersion"),
                CoordinateSpace.NORMALIZED_ZERO_TO_ONE,
                CellWeight.RAW_GAZE_SAMPLE_COUNT,
                false,
                requiredInt(heatmap, "rows"),
                requiredInt(heatmap, "columns"),
                cellCounts,
                requiredLong(summary, "minimumSequence"),
                requiredLong(summary, "maximumSequence"),
                requiredLong(summary, "highSequence"),
                missingRanges,
                requiredLong(summary, "lateArrivalCount"),
                requiredBoolean(summary, "unknownSessionBoundary"),
                gaze,
                observedTimeRange);
    }

    private static Optional<HeatmapProjection.TimeRange> observedTimeRange(Document summary) {
        if (!summary.containsKey("observedTimeRange")) {
            throw new IllegalArgumentException("Missing field: observedTimeRange");
        }
        Object value = summary.get("observedTimeRange");
        if (value == null) return Optional.empty();
        if (!(value instanceof Document range)) {
            throw new IllegalArgumentException("Invalid document: observedTimeRange");
        }
        return Optional.of(new HeatmapProjection.TimeRange(
                Instant.parse(requiredString(range, "minimum")),
                Instant.parse(requiredString(range, "maximum"))));
    }

    private static Document requiredDocument(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Document nested)) throw new IllegalArgumentException("Missing document: " + field);
        return nested;
    }

    private static List<Document> requiredDocuments(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("Missing list: " + field);
        var result = new ArrayList<Document>(values.size());
        for (Object item : values) {
            if (!(item instanceof Document nested)) throw new IllegalArgumentException("Invalid document list: " + field);
            result.add(nested);
        }
        return List.copyOf(result);
    }

    private static List<Long> requiredLongList(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("Missing list: " + field);
        var result = new ArrayList<Long>(values.size());
        for (Object item : values) result.add(exactLong(item, field));
        return List.copyOf(result);
    }

    private static String requiredString(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Missing string: " + field);
        }
        return text;
    }

    private static boolean requiredBoolean(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("Missing boolean: " + field);
        return flag;
    }

    private static int requiredInt(Document document, String field) {
        long value = requiredLong(document, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Integer out of range: " + field);
        }
        return (int) value;
    }

    private static long requiredLong(Document document, String field) {
        return exactLong(document.get(field), field);
    }

    private static long exactLong(Object value, String field) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Missing number: " + field);
        long converted = number.longValue();
        if (!Double.isFinite(number.doubleValue()) || number.doubleValue() != converted) {
            throw new IllegalArgumentException("Invalid integer: " + field);
        }
        return converted;
    }

    private static void requireEqual(String actual, String expected, String message) {
        if (!actual.equals(expected)) throw new IllegalArgumentException(message);
    }

    @FunctionalInterface
    interface DocumentLookup {
        Uni<Document> execute();
    }

    private ReactiveMongoCollection<Document> collection() {
        return clients.get().getDatabase(database).getCollection(collectionName);
    }
}

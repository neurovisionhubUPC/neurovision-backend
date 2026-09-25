package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSummaryQueryPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsSummaryProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
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
import java.util.OptionalDouble;
import java.util.UUID;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Reads the stored summary without recalculating metrics or widening its identity. */
@ApplicationScoped
public class MongoAnalyticsSummaryQueryAdapter implements AnalyticsSummaryQueryPort {
    private final Instance<ReactiveMongoClient> clients;
    private final String database;
    private final String collectionName;

    @Inject
    public MongoAnalyticsSummaryQueryAdapter(Instance<ReactiveMongoClient> clients,
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

    static Bson identityFilter(AnalyticsProjectionIdentity identity) {
        return and(eq("therapySessionId", identity.therapySessionId().toString()),
                eq("streamId", identity.streamId().toString()),
                eq("calculationVersion", identity.calculationVersion()));
    }

    static Uni<Result> query(AnalyticsProjectionIdentity identity, DocumentLookup lookup) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(lookup, "lookup");
        return lookup.execute().map(document -> {
            if (document == null) return NotFound.INSTANCE;
            try {
                return new Found(reconstruct(document, identity));
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException("Invalid persisted analytics summary", malformed);
            }
        });
    }

    static AnalyticsSummaryProjection reconstruct(Document document, AnalyticsProjectionIdentity requested) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(requested, "requested");
        var persisted = new AnalyticsProjectionIdentity(
                UUID.fromString(requiredString(document, "therapySessionId")),
                UUID.fromString(requiredString(document, "streamId")),
                requiredString(document, "calculationVersion"));
        if (!persisted.equals(requested)
                || !AnalyticsCalculationProfile.BASELINE_V1.calculationVersion()
                        .equals(persisted.calculationVersion())) {
            throw new IllegalArgumentException("Persisted summary identity does not match query");
        }
        long revision = requiredLong(document, "admittedBatchCount");
        Document source = requiredDocument(document, "summary");
        if (!persisted.calculationVersion().equals(requiredString(source, "profile"))
                || !persisted.therapySessionId().toString().equals(requiredString(source, "therapySessionId"))
                || !persisted.streamId().toString().equals(requiredString(source, "streamId"))) {
            throw new IllegalArgumentException("Summary identity metadata is inconsistent");
        }
        var summary = reconstructSummary(source);
        return new AnalyticsSummaryProjection(persisted, revision, summary);
    }

    private static BaselineStreamSummary reconstructSummary(Document source) {
        long minimumSequence = requiredLong(source, "minimumSequence");
        long maximumSequence = requiredLong(source, "maximumSequence");
        if (requiredLong(source, "highSequence") != maximumSequence) {
            throw new IllegalArgumentException("Persisted highSequence does not match maximumSequence");
        }
        var rawRanges = requiredDocuments(source, "missingSequenceRanges");
        var ranges = rawRanges.stream()
                .map(range -> new BaselineStreamSummary.SequenceRange(
                        requiredLong(range, "fromInclusive"), requiredLong(range, "toInclusive")))
                .toList();
        validateMissingSequenceRanges(ranges, minimumSequence, maximumSequence);
        Document rawCounts = requiredDocument(source, "sampleCounts");
        long gaze = requiredLong(rawCounts, "gaze");
        long gameplay = requiredLong(rawCounts, "gameplay");
        if (Math.addExact(gaze, gameplay) != requiredLong(rawCounts, "total")) {
            throw new IllegalArgumentException("Persisted sample counts are inconsistent");
        }
        Document rawHeatmap = requiredDocument(source, "heatmap");
        int rows = requiredInt(rawHeatmap, "rows");
        int columns = requiredInt(rawHeatmap, "columns");
        if (rows != AnalyticsCalculationProfile.BASELINE_V1.gridRows()
                || columns != AnalyticsCalculationProfile.BASELINE_V1.gridColumns()) {
            throw new IllegalArgumentException("Unsupported heatmap dimensions");
        }
        var heatmap = new BaselineStreamSummary.Heatmap(rows, columns,
                requiredLongList(rawHeatmap, "cellCounts", rows * columns));
        Document rawCoverage = requiredDocument(source, "coverage");
        Object rawBox = rawCoverage.get("boundingBox");
        Optional<BaselineStreamSummary.BoundingBox> box;
        if (rawBox == null && rawCoverage.containsKey("boundingBox")) {
            box = Optional.empty();
        } else if (rawBox instanceof Document value) {
            box = Optional.of(new BaselineStreamSummary.BoundingBox(
                    requiredDouble(value, "minimumX"), requiredDouble(value, "minimumY"),
                    requiredDouble(value, "maximumX"), requiredDouble(value, "maximumY"),
                    requiredDouble(value, "width"), requiredDouble(value, "height"),
                    requiredDouble(value, "area")));
        } else {
            throw new IllegalArgumentException("Invalid bounding box");
        }
        var coverage = new BaselineStreamSummary.Coverage(box,
                requiredLong(rawCoverage, "occupiedCellCount"), requiredDouble(rawCoverage, "occupiedCellRatio"));
        Document rawPath = requiredDocument(source, "gazePath");
        var path = new BaselineStreamSummary.PathMetrics(requiredDouble(rawPath, "totalDistance"),
                numeric(requiredDocument(rawPath, "velocity")),
                requiredLong(rawPath, "excludedNonPositiveDeltaCount"));
        Document rawGameplay = requiredDocument(source, "gameplay");
        var gameplayMetrics = new BaselineStreamSummary.GameplayMetrics(
                numeric(requiredDocument(rawGameplay, "playerTargetDistance")),
                ordered(requiredDocument(rawGameplay, "score")),
                ordered(requiredDocument(rawGameplay, "speed")),
                ordered(requiredDocument(rawGameplay, "progress")));
        return new BaselineStreamSummary(
                AnalyticsCalculationProfile.BASELINE_V1,
                requiredInt(source, "sourceSchemaVersion"),
                UUID.fromString(requiredString(source, "therapySessionId")),
                UUID.fromString(requiredString(source, "streamId")),
                minimumSequence,
                maximumSequence,
                ranges,
                requiredLong(source, "lateArrivalCount"),
                requiredBoolean(source, "unknownSessionBoundary"),
                observedTimeRange(source),
                new BaselineStreamSummary.SampleCounts(gaze, gameplay),
                numeric(requiredDocument(source, "confidence")),
                requiredLongList(source, "confidenceHistogram",
                        AnalyticsCalculationProfile.BASELINE_V1.confidenceBinCount()),
                heatmap,
                coverage,
                path,
                gameplayMetrics);
    }

    private static BaselineStreamSummary.NumericSummary numeric(Document document) {
        return new BaselineStreamSummary.NumericSummary(requiredLong(document, "count"),
                optionalDouble(document, "minimum"), optionalDouble(document, "maximum"),
                optionalDouble(document, "mean"));
    }

    private static BaselineStreamSummary.OrderedNumericSummary ordered(Document document) {
        return new BaselineStreamSummary.OrderedNumericSummary(numeric(requiredDocument(document, "distribution")),
                optionalDouble(document, "first"), optionalDouble(document, "last"),
                optionalDouble(document, "signedDelta"));
    }

    private static Optional<BaselineStreamSummary.TimeRange> observedTimeRange(Document source) {
        if (!source.containsKey("observedTimeRange")) throw new IllegalArgumentException("Missing observedTimeRange");
        Object value = source.get("observedTimeRange");
        if (value == null) return Optional.empty();
        if (!(value instanceof Document range)) throw new IllegalArgumentException("Invalid observedTimeRange");
        return Optional.of(new BaselineStreamSummary.TimeRange(
                Instant.parse(requiredString(range, "minimum")), Instant.parse(requiredString(range, "maximum"))));
    }

    private static OptionalDouble optionalDouble(Document document, String field) {
        if (!document.containsKey(field)) throw new IllegalArgumentException("Missing field: " + field);
        Object value = document.get(field);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(exactDouble(value, field));
    }

    private static void validateMissingSequenceRanges(List<BaselineStreamSummary.SequenceRange> ranges,
            long minimumSequence, long maximumSequence) {
        if (minimumSequence < 0 || maximumSequence < minimumSequence) {
            throw new IllegalArgumentException("Invalid persisted sequence bounds");
        }
        long previousEnd = minimumSequence;
        for (var range : ranges) {
            if (range.fromInclusive() <= previousEnd || range.toInclusive() >= maximumSequence
                    || (previousEnd != minimumSequence && range.fromInclusive() <= previousEnd + 1)) {
                throw new IllegalArgumentException("Persisted missing sequence ranges are inconsistent");
            }
            previousEnd = range.toInclusive();
        }
    }

    private static List<Document> requiredDocuments(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("Invalid list: " + field);
        var result = new ArrayList<Document>(values.size());
        for (Object item : values) {
            if (!(item instanceof Document nested)) throw new IllegalArgumentException("Invalid list: " + field);
            result.add(nested);
        }
        return List.copyOf(result);
    }

    private static List<Long> requiredLongList(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("Missing list: " + field);
        return requiredLongList(values, field);
    }

    private static List<Long> requiredLongList(Document document, String field, int expectedSize) {
        Object value = document.get(field);
        if (!(value instanceof List<?> values) || values.size() != expectedSize) {
            throw new IllegalArgumentException("Invalid bounded list: " + field);
        }
        return requiredLongList(values, field);
    }

    private static List<Long> requiredLongList(List<?> values, String field) {
        var result = new ArrayList<Long>(values.size());
        for (Object item : values) result.add(exactLong(item, field));
        return List.copyOf(result);
    }

    private static Document requiredDocument(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Document nested)) throw new IllegalArgumentException("Missing document: " + field);
        return nested;
    }

    private static String requiredString(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof String string) || string.isBlank()) throw new IllegalArgumentException("Invalid string: " + field);
        return string;
    }

    private static boolean requiredBoolean(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("Invalid boolean: " + field);
        return flag;
    }

    private static int requiredInt(Document document, String field) {
        long value = requiredLong(document, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Integer out of range: " + field);
        return (int) value;
    }

    private static long requiredLong(Document document, String field) {
        return exactLong(document.get(field), field);
    }

    private static double requiredDouble(Document document, String field) {
        return exactDouble(document.get(field), field);
    }

    private static long exactLong(Object value, String field) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Invalid number: " + field);
        long converted = number.longValue();
        if (!Double.isFinite(number.doubleValue()) || number.doubleValue() != converted) {
            throw new IllegalArgumentException("Invalid integer: " + field);
        }
        return converted;
    }

    private static double exactDouble(Object value, String field) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Invalid number: " + field);
        }
        return number.doubleValue();
    }

    @FunctionalInterface
    interface DocumentLookup { Uni<Document> execute(); }

    private ReactiveMongoCollection<Document> collection() {
        return clients.get().getDatabase(database).getCollection(collectionName);
    }
}

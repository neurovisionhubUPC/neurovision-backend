package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.compoundIndex;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.setOnInsert;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StreamSummaryProjectionPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StreamSummaryProjection;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MongoStreamSummaryProjectionAdapter implements StreamSummaryProjectionPort {
    private final Instance<ReactiveMongoClient> clients;
    private final String database;
    private final String collectionName;
    private final AtomicBoolean prepared = new AtomicBoolean();

    @Inject
    public MongoStreamSummaryProjectionAdapter(
            Instance<ReactiveMongoClient> clients,
            @ConfigProperty(name = "quarkus.mongodb.database") String database,
            @ConfigProperty(name = "analytics.summary.collection") String collectionName) {
        this.clients = clients;
        this.database = database;
        this.collectionName = collectionName;
    }

    @Override
    public Uni<WriteResult> storeIfNewer(StreamSummaryProjection projection) {
        return prepare().chain(() -> storeAtomically(upsert -> collection().updateOne(
                newerRevisionFilter(projection), update(projection), new UpdateOptions().upsert(upsert))));
    }

    static Uni<WriteResult> storeAtomically(AtomicUpdate attempt) {
        return attempt.execute(true)
                .map(MongoStreamSummaryProjectionAdapter::classify)
                .onFailure(MongoStreamSummaryProjectionAdapter::duplicateKey)
                .recoverWithUni(() -> attempt.execute(false)
                        .map(MongoStreamSummaryProjectionAdapter::classify));
    }

    private Uni<Void> prepare() {
        if (prepared.get()) return Uni.createFrom().voidItem();
        var identity = compoundIndex(ascending("therapySessionId"), ascending("streamId"),
                ascending("calculationVersion"));
        return collection().createIndex(identity,
                        new IndexOptions().unique(true).name("uq_analytics_stream_calculation"))
                .replaceWithVoid().invoke(() -> prepared.set(true));
    }

    static Bson newerRevisionFilter(StreamSummaryProjection projection) {
        return and(
                eq("therapySessionId", projection.identity().therapySessionId().toString()),
                eq("streamId", projection.identity().streamId().toString()),
                eq("calculationVersion", projection.identity().calculationVersion()),
                or(exists("admittedBatchCount", false),
                        lt("admittedBatchCount", projection.admittedBatchCount())));
    }

    static Bson update(StreamSummaryProjection projection) {
        return combine(
                setOnInsert("therapySessionId", projection.identity().therapySessionId().toString()),
                setOnInsert("streamId", projection.identity().streamId().toString()),
                setOnInsert("calculationVersion", projection.identity().calculationVersion()),
                set("admittedBatchCount", projection.admittedBatchCount()),
                set("summary", summaryDocument(projection.summary())));
    }

    static WriteResult classify(UpdateResult result) {
        if (!result.wasAcknowledged()) throw new IllegalStateException("MongoDB projection write unacknowledged");
        if (result.getUpsertedId() != null) return WriteResult.INSERTED;
        if (result.getModifiedCount() > 0) return WriteResult.ADVANCED;
        return WriteResult.NOT_ADVANCED;
    }

    static Document summaryDocument(BaselineStreamSummary summary) {
        var document = new Document("profile", summary.profile().calculationVersion())
                .append("sourceSchemaVersion", summary.sourceSchemaVersion())
                .append("therapySessionId", summary.therapySessionId().toString())
                .append("streamId", summary.streamId().toString())
                .append("minimumSequence", summary.minimumSequence())
                .append("maximumSequence", summary.maximumSequence())
                .append("highSequence", summary.highSequence())
                .append("missingSequenceRanges", summary.missingSequenceRanges().stream()
                        .map(range -> new Document("fromInclusive", range.fromInclusive())
                                .append("toInclusive", range.toInclusive()))
                        .toList())
                .append("lateArrivalCount", summary.lateArrivalCount())
                .append("unknownSessionBoundary", summary.unknownSessionBoundary())
                .append("observedTimeRange", summary.observedTimeRange()
                        .map(range -> new Document("minimum", range.minimum().toString())
                                .append("maximum", range.maximum().toString()))
                        .orElse(null))
                .append("sampleCounts", new Document("gaze", summary.sampleCounts().gaze())
                        .append("gameplay", summary.sampleCounts().gameplay())
                        .append("total", summary.sampleCounts().total()))
                .append("confidence", numeric(summary.confidence()))
                .append("confidenceHistogram", summary.confidenceHistogram())
                .append("heatmap", new Document("rows", summary.heatmap().rows())
                        .append("columns", summary.heatmap().columns())
                        .append("cellCounts", summary.heatmap().cellCounts()))
                .append("coverage", coverage(summary.coverage()))
                .append("gazePath", new Document("totalDistance", summary.gazePath().totalDistance())
                        .append("velocity", numeric(summary.gazePath().velocity()))
                        .append("excludedNonPositiveDeltaCount",
                                summary.gazePath().excludedNonPositiveDeltaCount()))
                .append("gameplay", new Document(
                        "playerTargetDistance", numeric(summary.gameplay().playerTargetDistance()))
                        .append("score", ordered(summary.gameplay().score()))
                        .append("speed", ordered(summary.gameplay().speed()))
                        .append("progress", ordered(summary.gameplay().progress())));
        return document;
    }

    private static Document numeric(BaselineStreamSummary.NumericSummary summary) {
        return new Document("count", summary.count())
                .append("minimum", optional(summary.minimum()))
                .append("maximum", optional(summary.maximum()))
                .append("mean", optional(summary.mean()));
    }

    private static Document ordered(BaselineStreamSummary.OrderedNumericSummary summary) {
        return new Document("distribution", numeric(summary.distribution()))
                .append("first", optional(summary.first()))
                .append("last", optional(summary.last()))
                .append("signedDelta", optional(summary.signedDelta()));
    }

    private static Document coverage(BaselineStreamSummary.Coverage coverage) {
        var box = coverage.boundingBox().map(value -> new Document("minimumX", value.minimumX())
                .append("minimumY", value.minimumY())
                .append("maximumX", value.maximumX())
                .append("maximumY", value.maximumY())
                .append("width", value.width())
                .append("height", value.height())
                .append("area", value.area())).orElse(null);
        return new Document("boundingBox", box)
                .append("occupiedCellCount", coverage.occupiedCellCount())
                .append("occupiedCellRatio", coverage.occupiedCellRatio());
    }

    private static Double optional(OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : null;
    }

    static boolean duplicateKey(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoWriteException write && write.getCode() == 11000) return true;
            if (cause instanceof MongoCommandException command && command.getErrorCode() == 11000) return true;
        }
        return false;
    }

    @FunctionalInterface
    interface AtomicUpdate {
        Uni<UpdateResult> execute(boolean upsert);
    }

    private ReactiveMongoCollection<Document> collection() {
        return clients.get().getDatabase(database).getCollection(collectionName);
    }
}

package com.neurovision.rehab.analytics.infrastructure.integration;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/** Opt-in-by-environment synthetic benchmark; reports measurements but has no timing threshold. */
class ReplayCountQueryCostHarnessIT {
    private static final String IMAGE = "mongo:8.0.14-noble";
    private static final int WARMUPS = 5;
    private static final int REPETITIONS = 20;
    private static final List<Integer> STREAM_SIZES = List.of(100, 1_000, 5_000);

    @Test
    void measuresReplayCountDocumentsTwicePerSyntheticStream() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is unavailable; replay count measurement was not run");

        try (var mongo = new MongoDBContainer(DockerImageName.parse(IMAGE))) {
            mongo.start();
            try (MongoClient client = MongoClients.create(mongo.getReplicaSetUrl())) {
                var database = client.getDatabase("replay-count-cost");
                var inbox = database.getCollection("telemetry-inbox");
                inbox.createIndex(new Document("batchId", 1), new com.mongodb.client.model.IndexOptions()
                        .unique(true).name("uq_analytics_batch_id"));
                inbox.createIndex(new Document("therapySessionId", 1).append("streamId", 1).append("sequence", 1),
                        new com.mongodb.client.model.IndexOptions().unique(true).name("uq_analytics_stream_sequence"));

                var buildInfo = database.runCommand(new Document("buildInfo", 1));
                System.out.printf("REPLAY_COUNT_COST_CONTEXT image=%s mongo=%s os=%s arch=%s java=%s processors=%d warmups=%d repetitions=%d%n",
                        IMAGE, buildInfo.getString("version"), System.getProperty("os.name"),
                        System.getProperty("os.arch"), System.getProperty("java.version"),
                        Runtime.getRuntime().availableProcessors(), WARMUPS, REPETITIONS);

                UUID sessionId = UUID.randomUUID();
                for (int size : STREAM_SIZES) {
                    UUID streamId = UUID.randomUUID();
                    insertSyntheticAdmittedStream(inbox, sessionId, streamId, size);
                    var filter = and(eq("therapySessionId", sessionId.toString()),
                            eq("streamId", streamId.toString()), eq("status", "ADMITTED"));
                    assertEquals(size, inbox.countDocuments(filter));

                    Document explain = explainCountDocuments(database, inbox.getNamespace().getCollectionName(), filter);
                    Document executionStats = explain.get("executionStats", Document.class);
                    Document winningPlan = explain.get("queryPlanner", Document.class)
                            .get("winningPlan", Document.class);
                    Set<String> indexes = new LinkedHashSet<>();
                    collectIndexNames(winningPlan, indexes);
                    collectIndexNames(executionStats.get("executionStages", Document.class), indexes);

                    long[] beforeCountNanos = new long[REPETITIONS];
                    long[] afterCountNanos = new long[REPETITIONS];
                    for (int i = 0; i < WARMUPS; i++) {
                        inbox.countDocuments(filter);
                        inbox.countDocuments(filter);
                    }
                    for (int i = 0; i < REPETITIONS; i++) {
                        long start = System.nanoTime();
                        long firstCount = inbox.countDocuments(filter);
                        beforeCountNanos[i] = System.nanoTime() - start;
                        assertEquals(size, firstCount);
                        start = System.nanoTime();
                        long secondCount = inbox.countDocuments(filter);
                        afterCountNanos[i] = System.nanoTime() - start;
                        assertEquals(size, secondCount);
                    }

                    System.out.printf("REPLAY_COUNT_COST_RESULT size=%d docsExamined=%s keysExamined=%s indexes=%s count1_p50_us=%d count1_p95_us=%d count2_p50_us=%d count2_p95_us=%d%n",
                            size, executionStats.get("totalDocsExamined"), executionStats.get("totalKeysExamined"),
                            indexes, percentileMicros(beforeCountNanos, .50), percentileMicros(beforeCountNanos, .95),
                            percentileMicros(afterCountNanos, .50), percentileMicros(afterCountNanos, .95));
                }
            }
        }
    }

    private static void insertSyntheticAdmittedStream(com.mongodb.client.MongoCollection<Document> inbox,
            UUID sessionId, UUID streamId, int size) {
        var batches = new ArrayList<Document>(size);
        for (int sequence = 0; sequence < size; sequence++) {
            batches.add(new Document("batchId", UUID.randomUUID().toString())
                    .append("therapySessionId", sessionId.toString())
                    .append("streamId", streamId.toString())
                    .append("sequence", (long) sequence)
                    .append("status", "ADMITTED")
                    .append("schemaVersion", 1)
                    .append("capturedAt", "2026-01-01T00:00:00Z")
                    .append("gazeSamples", List.of())
                    .append("gameplaySamples", List.of()));
        }
        inbox.insertMany(batches);
    }

    private static Document explainCountDocuments(com.mongodb.client.MongoDatabase database,
            String collection, org.bson.conversions.Bson filter) {
        var match = filter.toBsonDocument(Document.class, database.getCodecRegistry());
        var group = new Document("_id", null).append("n", new Document("$sum", 1));
        var aggregate = new Document("aggregate", collection)
                .append("pipeline", List.of(new Document("$match", match), new Document("$group", group)))
                .append("cursor", new Document());
        return database.runCommand(new Document("explain", aggregate).append("verbosity", "executionStats"));
    }

    private static void collectIndexNames(Object node, Set<String> indexes) {
        if (node instanceof Document document) {
            Object name = document.get("indexName");
            if (name instanceof String indexName) indexes.add(indexName);
            document.values().forEach(value -> collectIndexNames(value, indexes));
        } else if (node instanceof Iterable<?> values) {
            values.forEach(value -> collectIndexNames(value, indexes));
        }
    }

    private static long percentileMicros(long[] samples, double percentile) {
        long[] sorted = java.util.Arrays.stream(samples).sorted().toArray();
        int index = Math.min(sorted.length - 1, (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[index] / 1_000;
    }
}

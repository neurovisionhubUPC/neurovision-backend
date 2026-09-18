package com.neurovision.rehab.telemetry.infrastructure.persistence.timescale;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.telemetry.infrastructure.messaging.KafkaBrokerTestResource;
import com.neurovision.rehab.telemetry.interfaces.events.contracts.TelemetryBatchAcceptedV1;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/** Bounded synthetic observation, not a benchmark or production capacity claim. */
@QuarkusTest
@QuarkusTestResource(value = TimescaleTestResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = KafkaBrokerTestResource.class, restrictToAnnotatedClass = true)
class TelemetrySyntheticCapacityIT {
    private static final int BATCHES = 20;
    private static final int GAZE_PER_BATCH = 5;
    private static final int GAMEPLAY_PER_BATCH = 5;
    private static final Duration PERSISTENCE_WAIT = Duration.ofSeconds(30);

    @Inject Instance<DataSource> dataSource;
    @Inject ObjectMapper mapper;
    @Inject @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers;

    @Test
    void observesOneSequentialSyntheticStreamFromBrokerAckThroughTimescale() throws Exception {
        assertEquals(0, count("telemetry_batch_inbox"));
        assertEquals(0, count("telemetry_samples"));
        UUID sessionId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        String key = sessionId + ":" + streamId;
        Instant capturedAt = Instant.now().minusSeconds(3).truncatedTo(ChronoUnit.MILLIS);
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        long bytes = 0;
        long heapBefore = usedHeap();
        long started;
        long allBrokerAcknowledged;
        long allPersisted;
        try (var producer = new KafkaProducer<String, String>(properties)) {
            started = System.nanoTime();
            for (int sequence = 0; sequence < BATCHES; sequence++) {
                String json = mapper.writeValueAsString(event(sessionId, streamId, sequence, capturedAt));
                bytes += json.getBytes(StandardCharsets.UTF_8).length;
                producer.send(new ProducerRecord<>("telemetry-batch-accepted.v1", key, json))
                        .get(10, TimeUnit.SECONDS);
            }
            allBrokerAcknowledged = System.nanoTime();
            awaitPersisted();
            allPersisted = System.nanoTime();
        }
        long heapAfter = usedHeap();
        long ackMs = TimeUnit.NANOSECONDS.toMillis(allBrokerAcknowledged - started);
        long completionMs = TimeUnit.NANOSECONDS.toMillis(allPersisted - started);
        double batchesPerSecond = BATCHES * 1_000_000_000.0 / (allPersisted - started);
        System.out.printf(Locale.ROOT,
                "SYNTHETIC_TELEMETRY batches=%d gaze_per_batch=%d gameplay_per_batch=%d "
                        + "concurrency=1 payload_bytes=%d broker_ack_window_ms=%d "
                        + "ack_to_persist_observed_ms=%d total_completion_ms=%d "
                        + "observed_batches_per_second=%.2f test_jvm_heap_before_bytes=%d "
                        + "test_jvm_heap_after_bytes=%d test_jvm_max_heap_bytes=%d%n",
                BATCHES, GAZE_PER_BATCH, GAMEPLAY_PER_BATCH, bytes, ackMs,
                TimeUnit.NANOSECONDS.toMillis(allPersisted - allBrokerAcknowledged),
                completionMs, batchesPerSecond, heapBefore, heapAfter, Runtime.getRuntime().maxMemory());
    }

    private TelemetryBatchAcceptedV1 event(UUID sessionId, UUID streamId, int sequence, Instant capturedAt) {
        List<TelemetryBatchAcceptedV1.GazeSampleV1> gaze = new ArrayList<>(GAZE_PER_BATCH);
        List<TelemetryBatchAcceptedV1.GameplaySampleV1> gameplay = new ArrayList<>(GAMEPLAY_PER_BATCH);
        for (int index = 0; index < GAZE_PER_BATCH; index++) {
            gaze.add(new TelemetryBatchAcceptedV1.GazeSampleV1(index * 10L, .2, .8, .9));
            gameplay.add(new TelemetryBatchAcceptedV1.GameplaySampleV1(index * 10L + 5,
                    .1, .2, .3, .4, index, 1.5, .6));
        }
        return new TelemetryBatchAcceptedV1(1, UUID.randomUUID(), UUID.randomUUID(), sessionId,
                streamId, sequence, capturedAt, capturedAt.plusSeconds(1), Instant.now(),
                UUID.randomUUID(), null, gaze, gameplay);
    }

    private void awaitPersisted() throws Exception {
        long deadline = System.nanoTime() + PERSISTENCE_WAIT.toNanos();
        do {
            if (count("telemetry_batch_inbox") == BATCHES
                    && count("telemetry_samples") == BATCHES * (GAZE_PER_BATCH + GAMEPLAY_PER_BATCH)) {
                return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertEquals(BATCHES, count("telemetry_batch_inbox"));
        assertEquals(BATCHES * (GAZE_PER_BATCH + GAMEPLAY_PER_BATCH), count("telemetry_samples"));
    }

    private int count(String table) throws SQLException {
        try (var connection = dataSource.get().getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}

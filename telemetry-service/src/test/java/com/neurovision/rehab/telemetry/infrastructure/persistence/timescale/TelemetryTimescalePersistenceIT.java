package com.neurovision.rehab.telemetry.infrastructure.persistence.timescale;

import static io.restassured.RestAssured.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import com.neurovision.rehab.telemetry.application.ports.out.persistence.TelemetryPersistenceRejectedException;
import com.neurovision.rehab.telemetry.infrastructure.messaging.KafkaBrokerTestResource;
import com.neurovision.rehab.telemetry.interfaces.events.contracts.TelemetryBatchAcceptedV1;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.Collections;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.TopicPartition;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/** Requires a locally available Timescale image; deliberately excluded from `mvn test`. */
@QuarkusTest
@QuarkusTestResource(value = TimescaleTestResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = KafkaBrokerTestResource.class, restrictToAnnotatedClass = true)
class TelemetryTimescalePersistenceIT {
    @Inject Instance<DataSource> dataSource;
    @Inject PgTelemetryBatchPersistenceAdapter persistence;
    @Inject ObjectMapper mapper;
    @Inject @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers;

    @Test
    void migrationDeduplicatesRedeliveryAndQuarantinesConflictingBatchReuse() throws Exception {
        assertEquals(1, count("SELECT count(*) FROM timescaledb_information.hypertables "
                + "WHERE hypertable_name = 'telemetry_samples'"));
        var batchId = UUID.randomUUID();
        var sessionId = UUID.randomUUID();
        var streamId = UUID.randomUUID();
        var capturedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).minusSeconds(3);
        var key = sessionId + ":" + streamId;
        var first = event(UUID.randomUUID(), batchId, sessionId, streamId, capturedAt);
        var retry = event(UUID.randomUUID(), batchId, sessionId, streamId, capturedAt);
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (var producer = new KafkaProducer<String, String>(properties)) {
            var firstRecord = producer.send(new ProducerRecord<>("telemetry-batch-accepted.v1", key,
                    mapper.writeValueAsString(first))).get();
            awaitCount("SELECT count(*) FROM telemetry_samples WHERE batch_id = '" + batchId + "'", 2);
            var retryRecord = producer.send(new ProducerRecord<>("telemetry-batch-accepted.v1", key,
                    mapper.writeValueAsString(retry))).get();
            assertEquals(firstRecord.partition(), retryRecord.partition());
            var partition = new TopicPartition("telemetry-batch-accepted.v1", retryRecord.partition());
            awaitCommitted(partition, retryRecord.offset() + 1);
            awaitCount("SELECT count(*) FROM telemetry_batch_inbox WHERE batch_id = '" + batchId + "'", 1);
            assertEquals(2, count("SELECT count(*) FROM telemetry_samples WHERE batch_id = '" + batchId + "'"));
            assertPersistedSampleValues(batchId, capturedAt);
            assertEquals(200, get("/q/health/live").statusCode());

            // The same batchId with changed logical samples is poison, not a second execution.
            var conflicting = event(UUID.randomUUID(), batchId, sessionId, streamId, capturedAt, .6);
            String conflictingJson = mapper.writeValueAsString(conflicting);
            var conflictRecord = producer.send(new ProducerRecord<>("telemetry-batch-accepted.v1", key,
                    conflictingJson)).get();
            assertEquals(retryRecord.partition(), conflictRecord.partition());
            var quarantined = awaitDlqPayload(conflictingJson);
            assertEquals(key, quarantined.key());
            assertEquals("PERSISTENCE_REJECTED", header(quarantined, "telemetry-failure-code"));
            assertEquals("telemetry-batch-accepted.v1", header(quarantined, "telemetry-source-topic"));
            assertEquals(Long.toString(conflictRecord.offset()), header(quarantined, "telemetry-source-offset"));
            awaitCommitted(partition, conflictRecord.offset() + 1);
            assertEquals(200, get("/q/health/live").statusCode());
            assertEquals(1, count("SELECT count(*) FROM telemetry_batch_inbox WHERE batch_id = '" + batchId + "'"));
            assertEquals(2, count("SELECT count(*) FROM telemetry_samples WHERE batch_id = '" + batchId + "'"));

            String invalidJson = "{\"syntheticSensitiveSample\":\"not-for-logs\"}";
            var invalidRecord = producer.send(new ProducerRecord<>("telemetry-batch-accepted.v1", key,
                    invalidJson)).get();
            var invalidQuarantine = awaitDlqPayload(invalidJson);
            assertEquals("INVALID_EVENT", header(invalidQuarantine, "telemetry-failure-code"));
            awaitCommitted(partition, invalidRecord.offset() + 1);
        }
    }

    @Test
    void secondInsertChunkFailureRollsBackInboxAndFirstChunk() throws Exception {
        var batchId = UUID.randomUUID();
        var sessionId = UUID.randomUUID();
        var streamId = UUID.randomUUID();
        var samples = new ArrayList<TelemetryBatch.GazeSample>();
        for (int index = 0; index < 256; index++) {
            samples.add(new TelemetryBatch.GazeSample(index, .2, .5, .8));
        }
        // The adapter writes chunks of 256. The 257th row violates the DB constraint.
        samples.add(new TelemetryBatch.GazeSample(256, 2.0, .5, .8));
        var batch = new TelemetryBatch(1, batchId, sessionId, streamId, 1,
                Instant.now().minusSeconds(2), Instant.now().minusSeconds(1),
                samples, List.of());
        assertThrows(TelemetryPersistenceRejectedException.class,
                () -> persistence.persist(batch, Instant.now(), "a".repeat(64))
                .await().atMost(Duration.ofSeconds(10)));
        assertEquals(0, count("SELECT count(*) FROM telemetry_batch_inbox WHERE batch_id = '" + batchId + "'"));
        assertEquals(0, count("SELECT count(*) FROM telemetry_samples WHERE batch_id = '" + batchId + "'"));
        assertEquals(0, count("SELECT count(*) FROM telemetry_stream_progress WHERE therapy_session_id = '"
                + sessionId + "' AND stream_id = '" + streamId + "'"));
    }

    private TelemetryBatchAcceptedV1 event(UUID eventId, UUID batchId, UUID sessionId,
            UUID streamId, Instant capturedAt) {
        return event(eventId, batchId, sessionId, streamId, capturedAt, .2);
    }

    private TelemetryBatchAcceptedV1 event(UUID eventId, UUID batchId, UUID sessionId,
            UUID streamId, Instant capturedAt, double gazeX) {
        return new TelemetryBatchAcceptedV1(1, eventId, batchId, sessionId, streamId, 1,
                capturedAt, capturedAt.plusSeconds(1), Instant.now(), UUID.randomUUID(), "trace",
                List.of(new TelemetryBatchAcceptedV1.GazeSampleV1(0, gazeX, .7, .9)),
                List.of(new TelemetryBatchAcceptedV1.GameplaySampleV1(1, .1, .2, .3, .4, 5, 1.5, .6)));
    }

    private org.apache.kafka.clients.consumer.ConsumerRecord<String, String> awaitDlqPayload(String expected)
            throws Exception {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "telemetry-dlq-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(Collections.singleton("telemetry-batch-accepted.v1.dlq"));
            long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < end) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (expected.equals(record.value())) return record;
                }
            }
        }
        throw new AssertionError("Expected telemetry record was not broker-acknowledged to DLQ");
    }

    private String header(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record, String name) {
        var value = record.headers().lastHeader(name);
        return value == null ? null : new String(value.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void awaitCommitted(TopicPartition partition, long expectedNextOffset) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < end) {
            if (committedOffset(partition) >= expectedNextOffset) return;
            Thread.sleep(100);
        }
        assertTrue(committedOffset(partition) >= expectedNextOffset,
                "Kafka may only commit an offset after the database transaction commits");
    }

    private long committedOffset(TopicPartition partition) throws Exception {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        try (var admin = AdminClient.create(properties)) {
            var offsets = admin.listConsumerGroupOffsets("telemetry-timescale-v1")
                    .partitionsToOffsetAndMetadata().get();
            var committed = offsets.get(partition);
            return committed == null ? -1 : committed.offset();
        }
    }

    private void assertPersistedSampleValues(UUID batchId, Instant capturedAt) throws Exception {
        try (var connection = dataSource.get().getConnection();
                var statement = connection.prepareStatement("""
                        SELECT sample_kind, sample_time, offset_ms, x, y, confidence,
                               player_x, player_y, target_x, target_y, score, speed, progress
                        FROM telemetry_samples WHERE batch_id = ? ORDER BY sample_time
                        """)) {
            statement.setObject(1, batchId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("GAZE", rows.getString("sample_kind"));
                assertEquals(capturedAt, rows.getObject("sample_time", java.time.OffsetDateTime.class).toInstant());
                assertEquals(0, rows.getLong("offset_ms"));
                assertEquals(.2, rows.getDouble("x"));
                assertEquals(.7, rows.getDouble("y"));
                assertEquals(.9, rows.getDouble("confidence"));

                assertTrue(rows.next());
                assertEquals("GAMEPLAY", rows.getString("sample_kind"));
                assertEquals(capturedAt.plusMillis(1),
                        rows.getObject("sample_time", java.time.OffsetDateTime.class).toInstant());
                assertEquals(1, rows.getLong("offset_ms"));
                assertEquals(.1, rows.getDouble("player_x"));
                assertEquals(.2, rows.getDouble("player_y"));
                assertEquals(.3, rows.getDouble("target_x"));
                assertEquals(.4, rows.getDouble("target_y"));
                assertEquals(5, rows.getLong("score"));
                assertEquals(1.5, rows.getDouble("speed"));
                assertEquals(.6, rows.getDouble("progress"));
                assertTrue(!rows.next());
            }
        }
    }

    private void awaitCount(String sql, int expected) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < end) {
            if (count(sql) == expected) return;
            Thread.sleep(100);
        }
        assertEquals(expected, count(sql));
    }

    private int count(String sql) throws Exception {
        try (var connection = dataSource.get().getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}

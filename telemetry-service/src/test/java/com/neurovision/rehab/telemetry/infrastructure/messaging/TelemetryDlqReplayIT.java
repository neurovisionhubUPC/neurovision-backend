package com.neurovision.rehab.telemetry.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.telemetry.application.internal.commandservices.TelemetryReplayCommandService;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryReplayPort.Position;
import com.neurovision.rehab.telemetry.infrastructure.persistence.timescale.TimescaleTestResource;
import com.neurovision.rehab.telemetry.interfaces.events.contracts.TelemetryBatchAcceptedV1;
import com.neurovision.rehab.telemetry.interfaces.operations.TelemetryDlqCli;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/** Uses cached local Kafka/Timescale images; not part of the ordinary unit test suite. */
@QuarkusTest
@QuarkusTestResource(value = TimescaleTestResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = KafkaBrokerTestResource.class, restrictToAnnotatedClass = true)
class TelemetryDlqReplayIT {
    @Inject ObjectMapper mapper;
    @Inject Instance<DataSource> dataSource;
    @Inject @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrap;

    @Test
    void metadataOnlyInspectionAndTwiceReplayKeepOneLogicalBatchWithFourAuditEvents() throws Exception {
        var batch = UUID.randomUUID();
        var session = UUID.randomUUID();
        var stream = UUID.randomUUID();
        var captured = Instant.now().minusSeconds(3);
        var event = new TelemetryBatchAcceptedV1(1, UUID.randomUUID(), batch, session, stream, 1,
                captured, captured.plusSeconds(1), Instant.now(), UUID.randomUUID(), "trace",
                List.of(new TelemetryBatchAcceptedV1.GazeSampleV1(0, .25, .75, .9)),
                List.of(new TelemetryBatchAcceptedV1.GameplaySampleV1(1, .1, .2, .3, .4, 5, 1.5, .6)));
        byte[] payload = mapper.writeValueAsBytes(event);
        var position = quarantine(payload, (session + ":" + stream).getBytes(StandardCharsets.UTF_8),
                "PERSISTENCE_RETRIES_EXHAUSTED");
        var adapter = new KafkaTelemetryReplayAdapter(bootstrap);
        var metadata = adapter.inspect(position);
        assertEquals("PERSISTENCE_RETRIES_EXHAUSTED", metadata.failureCode().name());
        assertEquals(payload.length, metadata.valueBytes());
        assertEquals(0, count(batch));
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        assertEquals(0, TelemetryDlqCli.run(new String[] {"--action", "inspect", "--bootstrap", bootstrap,
                "--partition", Integer.toString(position.partition()), "--offset", Long.toString(position.offset())},
                new PrintStream(output), new PrintStream(errors)));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("PERSISTENCE_RETRIES_EXHAUSTED"));
        assertTrue(!output.toString(StandardCharsets.UTF_8).contains("gazeSamples")
                && !output.toString(StandardCharsets.UTF_8).contains(session.toString()));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));

        var service = new TelemetryReplayCommandService(adapter);
        String confirmation = "REPLAY telemetry-batch-accepted.v1.dlq:" + position.partition()
                + ":" + position.offset();
        var first = service.replay(position, "test.operator", "RETRY_AFTER_RECOVERY", confirmation);
        awaitCommitted(first.targetPartition(), first.targetOffset() + 1);
        assertEquals(2, count(batch));
        var second = service.replay(position, "test.operator", "RETRY_AFTER_RECOVERY", confirmation);
        awaitCommitted(second.targetPartition(), second.targetOffset() + 1);
        assertEquals(2, count(batch));
        assertEquals(1, inboxCount(batch));

        var audits = readAudits(4);
        assertEquals(4, audits.size());
        assertEquals(2, audits.stream().filter(value -> value.contains("\"status\":\"REQUESTED\"")).count());
        assertEquals(2, audits.stream().filter(value -> value.contains("\"status\":\"COMPLETED\"")).count());
        assertTrue(audits.stream().noneMatch(value -> value.contains("gazeSamples") || value.contains("gameplaySamples")
                || value.contains(session.toString()) || value.contains(stream.toString())));
    }

    @Test
    void permanentPoisonIsInspectableButCannotLoopBackToSource() throws Exception {
        byte[] poison = "{\"privateCoordinate\":0.123}".getBytes(StandardCharsets.UTF_8);
        var position = quarantine(poison, "synthetic-key".getBytes(StandardCharsets.UTF_8), "INVALID_EVENT");
        var adapter = new KafkaTelemetryReplayAdapter(bootstrap);
        assertEquals("INVALID_EVENT", adapter.inspect(position).failureCode().name());
        assertThrows(IllegalArgumentException.class,
                () -> new TelemetryReplayCommandService(adapter).replay(position, "test.operator",
                        "RETRY_AFTER_RECOVERY", "REPLAY telemetry-batch-accepted.v1.dlq:"
                                + position.partition() + ":" + position.offset()));
    }

    private Position quarantine(byte[] payload, byte[] key, String code) throws Exception {
        var properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (var producer = new KafkaProducer<byte[], byte[]>(properties)) {
            var record = new ProducerRecord<>(KafkaTelemetryReplayAdapter.DLQ, 0, key, payload);
            record.headers().add("telemetry-failure-code", code.getBytes(StandardCharsets.UTF_8));
            record.headers().add("telemetry-source-topic",
                    KafkaTelemetryReplayAdapter.SOURCE.getBytes(StandardCharsets.UTF_8));
            record.headers().add("telemetry-source-partition", "0".getBytes(StandardCharsets.UTF_8));
            record.headers().add("telemetry-source-offset", "0".getBytes(StandardCharsets.UTF_8));
            var sent = producer.send(record).get();
            return new Position(sent.partition(), sent.offset());
        }
    }

    private void awaitCommitted(int partition, long minimumOffset) throws Exception {
        var properties = new Properties();
        properties.put("bootstrap.servers", bootstrap);
        try (var admin = AdminClient.create(properties)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                var offsets = admin.listConsumerGroupOffsets("telemetry-timescale-v1")
                        .partitionsToOffsetAndMetadata().get();
                var value = offsets.get(new TopicPartition(KafkaTelemetryReplayAdapter.SOURCE, partition));
                if (value != null && value.offset() >= minimumOffset) return;
                Thread.sleep(100);
            }
            throw new AssertionError("Replay source offset was not committed");
        }
    }

    private List<String> readAudits(int expected) throws Exception {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        var result = new ArrayList<String>();
        try (var consumer = new KafkaConsumer<byte[], byte[]>(properties)) {
            var partition = new TopicPartition(KafkaTelemetryReplayAdapter.AUDIT, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (result.size() < expected && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    result.add(new String(record.value(), StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }

    private int count(UUID batchId) throws Exception {
        return sqlCount("SELECT count(*) FROM telemetry_samples WHERE batch_id = '" + batchId + "'");
    }

    private int inboxCount(UUID batchId) throws Exception {
        return sqlCount("SELECT count(*) FROM telemetry_batch_inbox WHERE batch_id = '" + batchId + "'");
    }

    private int sqlCount(String query) throws Exception {
        try (var connection = dataSource.get().getConnection();
                var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}

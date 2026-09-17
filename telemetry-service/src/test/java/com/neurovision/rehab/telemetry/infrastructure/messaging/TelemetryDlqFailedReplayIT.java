package com.neurovision.rehab.telemetry.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryReplayPort.Position;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Verifies that a failed source publish retains a metadata-only terminal audit. */
class TelemetryDlqFailedReplayIT {
    @Test
    void unavailableSourceAppendsRequestedAndFailedWithoutPayloadInAudit() throws Exception {
        try (var broker = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")) {
            broker.start();
            String bootstrap = broker.getBootstrapServers();
            var adminProperties = new Properties();
            adminProperties.put("bootstrap.servers", bootstrap);
            try (var admin = AdminClient.create(adminProperties)) {
                admin.createTopics(List.of(
                        new NewTopic(KafkaTelemetryReplayAdapter.DLQ, 1, (short) 1),
                        new NewTopic(KafkaTelemetryReplayAdapter.AUDIT, 1, (short) 1)))
                        .all().get();
            }
            byte[] sensitive = "{\"gazeSamples\":[0.123]}".getBytes(StandardCharsets.UTF_8);
            var position = sendDlq(bootstrap, sensitive);
            var adapter = new KafkaTelemetryReplayAdapter(bootstrap);
            assertThrows(IllegalStateException.class,
                    () -> adapter.replay(position, "test.operator", "RETRY_AFTER_RECOVERY"));
            var audit = readAudit(bootstrap);
            assertEquals(2, audit.size());
            assertTrue(audit.get(0).contains("\"status\":\"REQUESTED\""));
            assertTrue(audit.get(1).contains("\"status\":\"FAILED\""));
            assertTrue(audit.get(1).contains("\"deliveryUncertain\":true"));
            assertTrue(audit.stream().noneMatch(value -> value.contains("gazeSamples") || value.contains("0.123")));
        }
    }

    private Position sendDlq(String bootstrap, byte[] value) throws Exception {
        var properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (var producer = new KafkaProducer<byte[], byte[]>(properties)) {
            var record = new ProducerRecord<>(KafkaTelemetryReplayAdapter.DLQ, 0,
                    "synthetic-key".getBytes(StandardCharsets.UTF_8), value);
            record.headers().add("telemetry-failure-code", "PERSISTENCE_RETRIES_EXHAUSTED"
                    .getBytes(StandardCharsets.UTF_8));
            record.headers().add("telemetry-source-topic", KafkaTelemetryReplayAdapter.SOURCE
                    .getBytes(StandardCharsets.UTF_8));
            record.headers().add("telemetry-source-partition", "0".getBytes(StandardCharsets.UTF_8));
            record.headers().add("telemetry-source-offset", "0".getBytes(StandardCharsets.UTF_8));
            var sent = producer.send(record).get();
            return new Position(sent.partition(), sent.offset());
        }
    }

    private List<String> readAudit(String bootstrap) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        var result = new ArrayList<String>();
        try (var consumer = new KafkaConsumer<byte[], byte[]>(properties)) {
            var partition = new TopicPartition(KafkaTelemetryReplayAdapter.AUDIT, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (result.size() < 2 && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    result.add(new String(record.value(), StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }
}

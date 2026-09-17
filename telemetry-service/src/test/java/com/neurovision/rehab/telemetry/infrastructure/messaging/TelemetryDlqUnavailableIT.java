package com.neurovision.rehab.telemetry.infrastructure.messaging;

import static io.restassured.RestAssured.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/** A missing DLQ must stop consumption without committing the poison source offset. */
@QuarkusTest
@QuarkusTestResource(value = KafkaBrokerWithoutDlqTestResource.class, restrictToAnnotatedClass = true)
class TelemetryDlqUnavailableIT {
    @Inject @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers;

    @Test
    void missingDlqLeavesPoisonOffsetUncommitted() throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (var producer = new KafkaProducer<String, String>(properties)) {
            var source = producer.send(new ProducerRecord<>("telemetry-batch-accepted.v1",
                    UUID.randomUUID().toString(), "{\"syntheticSensitiveSample\":\"not-for-logs\"}"))
                    .get();
            var partition = new TopicPartition("telemetry-batch-accepted.v1", source.partition());
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && get("/q/health/live").statusCode() != 503) {
                Thread.sleep(100);
            }
            assertEquals(503, get("/q/health/live").statusCode());
            assertTrue(committedOffset(partition) < source.offset() + 1,
                    "No DLQ broker acknowledgement means no source offset commit");
        }
    }

    private long committedOffset(TopicPartition partition) throws Exception {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        try (var admin = AdminClient.create(properties)) {
            var committed = admin.listConsumerGroupOffsets("telemetry-timescale-v1")
                    .partitionsToOffsetAndMetadata().get().get(partition);
            return committed == null ? -1 : committed.offset();
        }
    }
}

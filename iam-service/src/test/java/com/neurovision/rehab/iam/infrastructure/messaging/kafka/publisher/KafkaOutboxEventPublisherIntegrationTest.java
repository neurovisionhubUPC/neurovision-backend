package com.neurovision.rehab.iam.infrastructure.messaging.kafka.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.iam.infrastructure.messaging.outbox.ClaimedOutboxEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

@QuarkusTest
class KafkaOutboxEventPublisherIntegrationTest {

    private static final String TOPIC = "patient-account-invited.v1";

    @Inject
    KafkaOutboxEventPublisher publisher;

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Test
    void shouldDeliverPatientAccountInvitedToKafka() {
        var aggregateId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        var eventId = UUID.randomUUID();
        var payload = new ObjectMapper().createObjectNode()
                .put("eventId", eventId.toString())
                .put("patientSubject", "patient-sub");
        var event = new ClaimedOutboxEvent(
                eventId,
                aggregateId,
                "PatientAccountInvited",
                (short) 1,
                payload,
                Instant.parse("2026-08-10T15:00:00Z"),
                1);

        try (var consumer = new KafkaConsumer<String, String>(consumerProperties())) {
            consumer.subscribe(List.of(TOPIC));
            consumer.poll(Duration.ofSeconds(1));

            publisher.publish(event);

            var records = consumer.poll(Duration.ofSeconds(10));
            assertFalse(records.isEmpty());
            var record = records.records(TOPIC).iterator().next();
            assertEquals(aggregateId.toString(), record.key());
            assertEquals(payload.toString(), record.value());
        }
    }

    private Properties consumerProperties() {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "iam-outbox-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return properties;
    }
}

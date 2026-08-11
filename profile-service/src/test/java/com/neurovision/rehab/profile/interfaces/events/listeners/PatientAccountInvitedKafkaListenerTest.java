package com.neurovision.rehab.profile.interfaces.events.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PatientAccountInvitedKafkaListenerTest {

    private static final String SOURCE_TOPIC = "patient-account-invited.v1";
    private static final String DLQ_TOPIC = "patient-account-invited.v1.dlq";

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Inject
    UserProfileRepository profiles;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery("DELETE FROM inbox_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM user_profiles").executeUpdate();
        });
    }

    @Test
    void shouldConsumeTheExternalEventAndPersistThePatientProjection() throws Exception {
        var eventId = UUID.fromString("36191b86-70a6-4f40-9dbf-a4ef6fc52f3c");
        var patientSubject = "kafka-patient-cognito-sub";
        var payload = """
                {
                  "eventId": "%s",
                  "invitationId": "6a4af241-3703-4940-888d-1e64f0f1c337",
                  "invitedByDoctor": "doctor-cognito-sub",
                  "patientSubject": "%s",
                  "occurredAt": "2026-08-10T21:04:30Z"
                }
                """.formatted(eventId, patientSubject);

        send(SOURCE_TOPIC, "6a4af241-3703-4940-888d-1e64f0f1c337", payload);

        awaitProfile(patientSubject, Duration.ofSeconds(15));
        assertEquals(1L, countInboxEvent(eventId));
    }

    @Test
    void shouldRetryAndRoutePermanentlyInvalidPayloadsToTheDlq() throws Exception {
        var invalidPayload = "{\"eventId\":\"not-a-uuid\",\"marker\":\"dlq-test\"}";
        try (var consumer = createConsumer()) {
            consumer.subscribe(java.util.List.of(DLQ_TOPIC));
            send(SOURCE_TOPIC, "invalid-invitation", invalidPayload);

            var record = pollForValue(consumer, invalidPayload, Duration.ofSeconds(20));

            assertEquals(invalidPayload, record.value());
            var reason = record.headers().lastHeader("dead-letter-reason");
            assertNotNull(reason);
            assertEquals(
                    "Invalid PatientAccountInvitedV1 payload",
                    new String(reason.value(), StandardCharsets.UTF_8));
        }
    }

    private void send(String topic, String key, String payload) throws Exception {
        var properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        try (var producer = new KafkaProducer<String, String>(properties)) {
            producer.send(new ProducerRecord<>(topic, key, payload)).get();
        }
    }

    private KafkaConsumer<String, String> createConsumer() {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "profile-dlq-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(properties);
    }

    private static org.apache.kafka.clients.consumer.ConsumerRecord<String, String> pollForValue(
            KafkaConsumer<String, String> consumer, String expected, Duration timeout) {
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (var record : consumer.poll(Duration.ofMillis(250))) {
                if (expected.equals(record.value())) {
                    return record;
                }
            }
        }
        throw new AssertionError("Expected record was not received from the dead-letter topic");
    }

    private void awaitProfile(String patientSubject, Duration timeout) throws InterruptedException {
        var deadline = Instant.now().plus(timeout);
        var subject = new CognitoSubject(patientSubject);
        while (Instant.now().isBefore(deadline)) {
            if (profiles.findByCognitoSubject(subject).isPresent()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Patient profile was not persisted before the timeout");
    }

    private long countInboxEvent(UUID eventId) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM inbox_events WHERE event_id = :eventId")
                        .setParameter("eventId", eventId)
                        .getSingleResult())
                .longValue());
    }
}

package com.neurovision.rehab.telemetry.infrastructure.messaging;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.telemetry.interfaces.rest.resource.ClinicalAuthorizationStubResource;
import com.neurovision.rehab.telemetry.interfaces.rest.resource.TelemetryRealOidcProfile;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
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
@TestProfile(TelemetryRealOidcProfile.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
@QuarkusTestResource(value = ClinicalAuthorizationStubResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = KafkaBrokerTestResource.class, restrictToAnnotatedClass = true)
class KafkaTelemetryBatchPublisherIT {
    @Inject ObjectMapper mapper;
    @Inject @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers;

    @Test
    void authenticatedHttpRetryGetsBrokerAckAndProducesDistinctAttemptsOfOneBatch() throws Exception {
        var fixture = new String(getClass().getResourceAsStream(
                "/fixtures/telemetry/v1/dual-typed-batch.json").readAllBytes(), StandardCharsets.UTF_8);
        var token = Jwt.subject("patient-subject").issuer("https://server.example.com")
                .claim("client_id", "test-client").claim("token_use", "access")
                .claim("cognito:groups", List.of("PATIENT"))
                .expiresAt(Instant.now().plusSeconds(300).getEpochSecond()).sign();
        given().auth().oauth2(token).contentType("application/json").body(fixture)
                .post("/api/v1/telemetry/batches").then().statusCode(202);
        given().auth().oauth2(token).contentType("application/json").body(fixture)
                .post("/api/v1/telemetry/batches").then().statusCode(202);

        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "telemetry-phase-4-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of("telemetry-batch-accepted.v1"));
            var records = consumer.poll(Duration.ofSeconds(10));
            assertEquals(2, records.count());
            var iterator = records.iterator();
            var record = iterator.next();
            var retry = iterator.next();
            var request = mapper.readTree(fixture);
            assertEquals(request.get("therapySessionId").asText() + ":" + request.get("streamId").asText(),
                    record.key());
            var event = mapper.readTree(record.value());
            assertNotNull(event.get("eventId"));
            assertEquals(request.get("batchId"), event.get("batchId"));
            assertEquals(1, event.get("schemaVersion").asInt());
            var retriedEvent = mapper.readTree(retry.value());
            assertEquals(event.get("batchId"), retriedEvent.get("batchId"));
            org.junit.jupiter.api.Assertions.assertNotEquals(event.get("eventId"), retriedEvent.get("eventId"));
            assertEquals(record.key(), retry.key());
            assertEquals(record.partition(), retry.partition());
            assertFalse(record.value().contains(token));
            assertFalse(record.value().contains("patient-subject"));
        }
    }
}

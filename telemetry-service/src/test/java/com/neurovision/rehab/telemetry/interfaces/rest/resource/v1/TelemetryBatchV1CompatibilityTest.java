package com.neurovision.rehab.telemetry.interfaces.rest.resource.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.telemetry.application.internal.validation.TelemetryBatchValidator;
import com.neurovision.rehab.telemetry.application.internal.validation.TelemetryValidationPolicy;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import com.neurovision.rehab.telemetry.interfaces.rest.transform.TelemetryBatchV1Mapper;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class TelemetryBatchV1CompatibilityTest {

    @Inject
    ObjectMapper objectMapper;

    @Test
    void preservesTheVersionOneFixtureThroughSerializationAndMapping() throws Exception {
        byte[] fixtureBytes;
        try (InputStream fixture = getClass().getResourceAsStream("/fixtures/telemetry/v1/dual-typed-batch.json")) {
            fixtureBytes = fixture.readAllBytes();
        }

        TelemetryBatchV1 contract = objectMapper.readValue(fixtureBytes, TelemetryBatchV1.class);
        TelemetryBatch domain = new TelemetryBatchV1Mapper().toDomain(contract);

        assertEquals(1, domain.schemaVersion());
        assertEquals(1, domain.gazeSamples().size());
        assertEquals(1, domain.gameplaySamples().size());
        assertTrue(new TelemetryBatchValidator().validate(
                domain,
                new TelemetryValidationPolicy(2, Duration.ZERO),
                Instant.parse("2026-09-12T12:00:06Z")).isAccepted());

        JsonNode expected = objectMapper.readTree(fixtureBytes);
        JsonNode serialized = objectMapper.readTree(objectMapper.writeValueAsBytes(contract));
        assertEquals(expected, serialized);
    }
}

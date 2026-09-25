package com.neurovision.rehab.analytics.interfaces.events.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neurovision.rehab.analytics.infrastructure.configuration.AnalyticsNativeReflectionConfiguration;
import com.neurovision.rehab.analytics.interfaces.events.contracts.TelemetryBatchAcceptedV1;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TelemetryBatchAcceptedV1DecoderTest {
    private static final String KEY = "00000000-0000-0000-0000-000000000003:00000000-0000-0000-0000-000000000004";
    private static final Instant NOW = Instant.parse("2026-09-17T10:00:05Z");
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void registersDynamicallyDecodedKafkaRecordsForNativeReflection() {
        var registration = AnalyticsNativeReflectionConfiguration.class.getAnnotation(RegisterForReflection.class);
        assertEquals(Set.of(TelemetryBatchAcceptedV1.class, TelemetryBatchAcceptedV1.GazeSampleV1.class,
                TelemetryBatchAcceptedV1.GameplaySampleV1.class),
                java.util.Arrays.stream(registration.targets()).collect(Collectors.toSet()));
    }

    @Test
    void acceptsCurrentSessionIdentityAndTimestampsLikeTheComposeSyntheticEvent() throws Exception {
        var event = (ObjectNode) mapper.readTree(fixture());
        var sessionId = UUID.randomUUID();
        var streamId = UUID.randomUUID();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("batchId", UUID.randomUUID().toString());
        event.put("therapySessionId", sessionId.toString());
        event.put("streamId", streamId.toString());
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("capturedAt", NOW.minusSeconds(2).toString());
        event.put("sentAt", NOW.minusSeconds(1).toString());
        event.put("serverReceivedAt", NOW.toString());
        event.put("sequence", 0);

        var decoded = decode(sessionId + ":" + streamId, mapper.writeValueAsString(event));
        assertEquals(sessionId, decoded.therapySessionId());
        assertEquals(streamId, decoded.streamId());
        assertEquals(1, decoded.gazeSamples().size());
        assertEquals(1, decoded.gameplaySamples().size());
    }

    @Test
    void acceptsFrozenFixtureAndFingerprintIgnoresAttemptMetadata() throws Exception {
        var first = decode(KEY, fixture());
        var retry = (ObjectNode) mapper.readTree(fixture());
        retry.put("eventId", "10000000-0000-0000-0000-000000000001");
        retry.put("serverReceivedAt", "2026-09-17T10:00:03Z");
        retry.put("correlationId", "10000000-0000-0000-0000-000000000005");
        retry.putNull("traceId");
        assertEquals(first.payloadSha256(), decode(KEY, mapper.writeValueAsString(retry)).payloadSha256());
        assertEquals(64, first.payloadSha256().length());

        ((ObjectNode) retry.withArray("gazeSamples").get(0)).put("x", .3);
        assertNotEquals(first.payloadSha256(), decode(KEY, mapper.writeValueAsString(retry)).payloadSha256());
    }

    @Test
    void failsClosedOnUnknownDuplicateVersionKeyAndInventedContext() throws Exception {
        var unknown = (ObjectNode) mapper.readTree(fixture());
        unknown.put("patientId", "forbidden");
        assertThrows(IllegalArgumentException.class, () -> decode(KEY, mapper.writeValueAsString(unknown)));
        var nested = (ObjectNode) mapper.readTree(fixture());
        ((ObjectNode) nested.withArray("gazeSamples").get(0)).put("stimulusId", "invented");
        assertThrows(IllegalArgumentException.class, () -> decode(KEY, mapper.writeValueAsString(nested)));
        var version = (ObjectNode) mapper.readTree(fixture());
        version.put("schemaVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> decode(KEY, mapper.writeValueAsString(version)));
        assertThrows(IllegalArgumentException.class, () -> decode("wrong", fixture()));
        assertThrows(IllegalArgumentException.class,
                () -> decode(KEY, fixture().replaceFirst("\\{", "{\"schemaVersion\":1,")));
    }

    @Test
    void rejectsMalformedOversizedInvalidTimesAndSamples() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> decode(KEY, "{"));
        assertThrows(IllegalArgumentException.class,
                () -> TelemetryBatchAcceptedV1Decoder.decode(KEY, fixture(), mapper, 10, 100,
                        Duration.ofSeconds(60), NOW));
        var invalidTime = (ObjectNode) mapper.readTree(fixture());
        invalidTime.put("capturedAt", "2026-09-17T10:00:02Z");
        assertThrows(IllegalArgumentException.class, () -> decode(KEY, mapper.writeValueAsString(invalidTime)));
        var invalidConfidence = (ObjectNode) mapper.readTree(fixture());
        ((ObjectNode) invalidConfidence.withArray("gazeSamples").get(0)).put("confidence", 1.1);
        assertThrows(IllegalArgumentException.class,
                () -> decode(KEY, mapper.writeValueAsString(invalidConfidence)));
        var empty = (ObjectNode) mapper.readTree(fixture());
        empty.withArray("gazeSamples").removeAll(); empty.withArray("gameplaySamples").removeAll();
        assertThrows(IllegalArgumentException.class, () -> decode(KEY, mapper.writeValueAsString(empty)));
    }

    @Test
    void rejectsMissingOrNullRequiredNumericFieldsBeforePrimitiveDefaults() throws Exception {
        var missingSequence = (ObjectNode) mapper.readTree(fixture());
        missingSequence.remove("sequence");
        assertRejected(missingSequence);

        var missingGaze = (ObjectNode) mapper.readTree(fixture());
        ((ObjectNode) missingGaze.withArray("gazeSamples").get(0)).remove("x");
        assertRejected(missingGaze);
        var nullGaze = (ObjectNode) mapper.readTree(fixture());
        ((ObjectNode) nullGaze.withArray("gazeSamples").get(0)).putNull("confidence");
        assertRejected(nullGaze);

        var missingGameplay = (ObjectNode) mapper.readTree(fixture());
        ((ObjectNode) missingGameplay.withArray("gameplaySamples").get(0)).remove("speed");
        assertRejected(missingGameplay);
        var nullGameplay = (ObjectNode) mapper.readTree(fixture());
        ((ObjectNode) nullGameplay.withArray("gameplaySamples").get(0)).putNull("score");
        assertRejected(nullGameplay);
    }

    private void assertRejected(ObjectNode event) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> decode(KEY, mapper.writeValueAsString(event)));
    }

    private com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch decode(
            String key, String json) {
        return TelemetryBatchAcceptedV1Decoder.decode(key, json, mapper, 65_536, 100,
                Duration.ofSeconds(60), NOW);
    }

    private String fixture() throws IOException {
        try (var stream = getClass().getResourceAsStream("/fixtures/telemetry/v1/accepted-event-wire.json")) {
            if (stream == null) throw new IOException("Analytics Kafka V1 fixture missing");
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}

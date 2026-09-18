package com.neurovision.rehab.telemetry.interfaces.events.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neurovision.rehab.telemetry.interfaces.events.contracts.TelemetryBatchAcceptedV1;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TelemetryBatchPersistenceEventAssemblerTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Instant now = Instant.parse("2026-09-17T10:00:05Z");
    private final UUID batchId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID streamId = UUID.randomUUID();

    @Test
    void normalizesBothSampleKindsAndDeduplicatesPublicationAttempts() throws Exception {
        var first = decode(event(UUID.randomUUID(), .2, now.minusSeconds(3)));
        var retry = decode(event(UUID.randomUUID(), .2, now.minusSeconds(2)));
        assertEquals(Instant.parse("2026-09-17T10:00:00Z"),
                TelemetryBatchPersistenceEventAssembler.sampleTime(first.batch().capturedAt(),
                        first.batch().gazeSamples().getFirst().offsetMs()));
        assertEquals(Instant.parse("2026-09-17T10:00:00.001Z"),
                TelemetryBatchPersistenceEventAssembler.sampleTime(first.batch().capturedAt(),
                        first.batch().gameplaySamples().getFirst().offsetMs()));
        assertEquals(first.payloadSha256(), retry.payloadSha256());
        assertEquals(64, first.payloadSha256().length());
        assertNotEquals(first.payloadSha256(), decode(event(UUID.randomUUID(), .3, now.minusSeconds(2))).payloadSha256());
    }

    @Test
    void acceptsOptionalNullTraceIdWithoutChangingSamplesOrFingerprint() throws Exception {
        var eventId = UUID.randomUUID();
        var receivedAt = now.minusSeconds(3);
        var withoutTrace = decode(event(eventId, .2, receivedAt, null));
        var withTrace = decode(event(eventId, .2, receivedAt, "trace"));

        assertEquals(1, withoutTrace.batch().gazeSamples().size());
        assertEquals(1, withoutTrace.batch().gameplaySamples().size());
        assertEquals(withTrace.batch(), withoutTrace.batch());
        assertEquals(withTrace.payloadSha256(), withoutTrace.payloadSha256());
    }

    @Test
    void rejectsWrongKeyExtraIdentityFieldAndDuplicateJsonKeys() throws Exception {
        var json = mapper.writeValueAsString(event(UUID.randomUUID(), .2, now.minusSeconds(3)));
        assertThrows(IllegalArgumentException.class, () ->
                TelemetryBatchPersistenceEventAssembler.decode("wrong-key", json, mapper,
                        65536, 100, Duration.ofSeconds(60), now));
        assertThrows(IllegalArgumentException.class, () -> decodeJson(json.replaceFirst("\\{", "{\"patientId\":\"secret\",")));
        assertThrows(IllegalArgumentException.class, () -> decodeJson(json.replaceFirst("\\{", "{\"schemaVersion\":2,")));
    }

    @Test
    void rejectsOutOfRangeSamplesAndFutureOrOverflowedTimes() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> decode(event(UUID.randomUUID(), 1.2, now.minusSeconds(3))));
        assertThrows(IllegalArgumentException.class, () -> decode(event(UUID.randomUUID(), .2, now.plusSeconds(120))));
        assertThrows(java.time.DateTimeException.class, () ->
                TelemetryBatchPersistenceEventAssembler.sampleTime(Instant.MAX, 1));
    }

    private TelemetryBatchPersistenceEventAssembler.Accepted decode(TelemetryBatchAcceptedV1 event) throws Exception {
        return decodeJson(mapper.writeValueAsString(event));
    }

    private TelemetryBatchPersistenceEventAssembler.Accepted decodeJson(String json) {
        return TelemetryBatchPersistenceEventAssembler.decode(sessionId + ":" + streamId,
                json, mapper, 65536, 100, Duration.ofSeconds(60), now);
    }

    private TelemetryBatchAcceptedV1 event(UUID eventId, double x, Instant receivedAt) {
        return event(eventId, x, receivedAt, "trace");
    }

    private TelemetryBatchAcceptedV1 event(UUID eventId, double x, Instant receivedAt, String traceId) {
        return new TelemetryBatchAcceptedV1(1, eventId, batchId, sessionId, streamId, 3,
                Instant.parse("2026-09-17T10:00:00Z"), Instant.parse("2026-09-17T10:00:01Z"),
                receivedAt, UUID.randomUUID(), traceId,
                List.of(new TelemetryBatchAcceptedV1.GazeSampleV1(0, x, .8, .9)),
                List.of(new TelemetryBatchAcceptedV1.GameplaySampleV1(1, .1, .2, .3, .4, 12, 1.5, .6)));
    }
}

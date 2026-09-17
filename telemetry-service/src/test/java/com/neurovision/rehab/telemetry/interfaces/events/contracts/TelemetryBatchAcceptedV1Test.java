package com.neurovision.rehab.telemetry.interfaces.events.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neurovision.rehab.telemetry.application.internal.validation.TelemetryValidationResult;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryPublicationPort.Publication;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import com.neurovision.rehab.telemetry.interfaces.events.transform.TelemetryBatchEventAssembler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TelemetryBatchAcceptedV1Test {
    @Test
    void mapsDistinctAttemptsToOneLogicalBatchWithoutIdentityOrVideo() throws Exception {
        var batch = new TelemetryBatch(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 3,
                Instant.parse("2026-09-17T10:00:00Z"), Instant.parse("2026-09-17T10:00:01Z"),
                List.of(new TelemetryBatch.GazeSample(0, .2, .8, .9)),
                List.of(new TelemetryBatch.GameplaySample(1, .1, .2, .3, .4, 12, 1.5, .6)));
        var publication = new Publication(TelemetryValidationResult.accepted(batch,
                Instant.parse("2026-09-17T10:00:02Z")), UUID.randomUUID(), "trace");
        var first = TelemetryBatchEventAssembler.toV1(publication);
        var second = TelemetryBatchEventAssembler.toV1(publication);
        assertNotEquals(first.eventId(), second.eventId());
        assertEquals(batch.batchId(), first.batchId());
        assertEquals(first.batchId(), second.batchId());
        assertEquals(batch.therapySessionId() + ":" + batch.streamId(),
                TelemetryBatchEventAssembler.partitionKey(publication));
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var json = mapper.writeValueAsString(first);
        var fixture = getClass().getResourceAsStream("/fixtures/telemetry/v1/accepted-event.json");
        var expected = mapper.readTree(fixture);
        var actual = mapper.readTree(json);
        ((com.fasterxml.jackson.databind.node.ObjectNode) actual).put("eventId", "EVENT_ID");
        ((com.fasterxml.jackson.databind.node.ObjectNode) actual).put("batchId", "BATCH_ID");
        ((com.fasterxml.jackson.databind.node.ObjectNode) actual).put("therapySessionId", "THERAPY_SESSION_ID");
        ((com.fasterxml.jackson.databind.node.ObjectNode) actual).put("streamId", "STREAM_ID");
        ((com.fasterxml.jackson.databind.node.ObjectNode) actual).put("correlationId", "CORRELATION_ID");
        assertEquals(expected, actual);
        for (var forbidden : List.of("accessToken", "patientId", "patientSubject", "videoFrame", "image", "frame")) {
            assertFalse(json.contains(forbidden));
        }
    }
}

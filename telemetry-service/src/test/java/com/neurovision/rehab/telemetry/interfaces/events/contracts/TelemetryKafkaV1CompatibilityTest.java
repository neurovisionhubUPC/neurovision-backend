package com.neurovision.rehab.telemetry.interfaces.events.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neurovision.rehab.telemetry.application.internal.validation.TelemetryValidationResult;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryPublicationPort.Publication;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import com.neurovision.rehab.telemetry.interfaces.events.transform.TelemetryBatchEventAssembler;
import com.neurovision.rehab.telemetry.interfaces.events.transform.TelemetryBatchPersistenceEventAssembler;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TelemetryKafkaV1CompatibilityTest {
    private static final UUID BATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID STREAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final Instant NOW = Instant.parse("2026-09-17T10:00:05Z");
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void producerMatchesFrozenWireFixtureAndRetryUsesNewAttemptId() throws Exception {
        var batch = new TelemetryBatch(1, BATCH_ID, SESSION_ID, STREAM_ID, 3,
                Instant.parse("2026-09-17T10:00:00Z"), Instant.parse("2026-09-17T10:00:01Z"),
                List.of(new TelemetryBatch.GazeSample(0, .2, .8, .9)),
                List.of(new TelemetryBatch.GameplaySample(1, .1, .2, .3, .4, 12, 1.5, .6)));
        var publication = new Publication(TelemetryValidationResult.accepted(batch,
                Instant.parse("2026-09-17T10:00:02Z")), CORRELATION_ID, "synthetic-trace");
        var first = TelemetryBatchEventAssembler.toV1(publication);
        var retry = TelemetryBatchEventAssembler.toV1(publication);
        assertNotEquals(first.eventId(), retry.eventId());
        assertEquals(first.batchId(), retry.batchId());
        assertEquals(SESSION_ID + ":" + STREAM_ID, TelemetryBatchEventAssembler.partitionKey(publication));

        // Compare the emitted JSON, not valueToTree: Jackson gives LongNode and IntNode
        // different equality even when both serialize to the same JSON integer.
        var actual = (ObjectNode) mapper.readTree(mapper.writeValueAsString(first));
        actual.put("eventId", "00000000-0000-0000-0000-000000000001");
        assertEquals(mapper.readTree(fixture()), actual);
        assertFalse(actual.has("sequenceQuality"));
        assertFalse(actual.has("missingSequenceStart"));
        assertFalse(actual.has("missingSequenceEnd"));
    }

    @Test
    void decoderAcceptsFrozenFixtureAndOptionalNullTrace() throws Exception {
        var accepted = decode(key(), fixture());
        assertEquals(BATCH_ID, accepted.batch().batchId());
        assertEquals(3, accepted.batch().sequence());
        assertEquals(1, accepted.batch().gazeSamples().size());
        assertEquals(1, accepted.batch().gameplaySamples().size());

        var withNullTrace = (ObjectNode) mapper.readTree(fixture());
        withNullTrace.putNull("traceId");
        assertEquals(accepted.payloadSha256(), decode(key(), mapper.writeValueAsString(withNullTrace)).payloadSha256());
    }

    @Test
    void decoderFailsClosedOnUnknownFieldsAndUnsupportedVersion() throws Exception {
        var rootField = (ObjectNode) mapper.readTree(fixture());
        rootField.put("patientId", "not-allowed");
        assertThrows(IllegalArgumentException.class, () -> decode(key(), mapper.writeValueAsString(rootField)));

        var nestedField = (ObjectNode) mapper.readTree(fixture());
        ((ObjectNode) nestedField.withArray("gazeSamples").get(0)).put("futureMetric", 1);
        assertThrows(IllegalArgumentException.class, () -> decode(key(), mapper.writeValueAsString(nestedField)));

        var futureVersion = (ObjectNode) mapper.readTree(fixture());
        futureVersion.put("schemaVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> decode(key(), mapper.writeValueAsString(futureVersion)));
    }

    @Test
    void decoderRejectsDuplicateKeysAndMismatchedPartitionKey() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> decode("wrong-key", fixture()));
        var duplicate = fixture().replaceFirst("\\{", "{\"schemaVersion\":1,");
        assertThrows(IllegalArgumentException.class, () -> decode(key(), duplicate));
    }

    private TelemetryBatchPersistenceEventAssembler.Accepted decode(String key, String json) {
        return TelemetryBatchPersistenceEventAssembler.decode(key, json, mapper,
                65_536, 100, Duration.ofSeconds(60), NOW);
    }

    private static String key() {
        return SESSION_ID + ":" + STREAM_ID;
    }

    private String fixture() throws IOException {
        try (var stream = getClass().getResourceAsStream("/fixtures/telemetry/v1/accepted-event-wire.json")) {
            if (stream == null) throw new IOException("Kafka V1 fixture missing");
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}

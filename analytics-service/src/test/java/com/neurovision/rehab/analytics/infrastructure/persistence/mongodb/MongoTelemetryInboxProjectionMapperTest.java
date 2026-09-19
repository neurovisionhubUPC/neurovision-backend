package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MongoTelemetryInboxProjectionMapperTest {

    @Test
    void persistsAndReconstructsTheCompleteAcceptedBatchContract() {
        var batch = batch("trace-123");
        var provenance = new TelemetryInboxPort.Provenance("telemetry-batch-accepted.v1", 2, 17, 512);
        var admittedAt = Instant.parse("2026-09-18T10:00:03Z");

        var document = MongoTelemetryInboxAdapter.admittedDocument(
                batch, TelemetryAdmissionQuality.LATE_ARRIVAL, provenance, admittedAt);
        var reconstructed = MongoTelemetryInboxAdapter.reconstruct(document);

        assertEquals(batch, reconstructed.batch());
        assertEquals(TelemetryAdmissionQuality.LATE_ARRIVAL, reconstructed.admissionQuality());
        assertEquals(batch.correlationId().toString(), document.getString("correlationId"));
        assertEquals("trace-123", document.getString("traceId"));
    }

    @Test
    void keepsTraceIdOptionalButFailsClosedWithoutRequiredCorrelationMetadata() {
        var withoutTrace = MongoTelemetryInboxAdapter.admittedDocument(
                batch(null), TelemetryAdmissionQuality.INITIAL,
                new TelemetryInboxPort.Provenance("topic", 0, 0, 100), Instant.parse("2026-09-18T10:00:03Z"));

        assertNull(MongoTelemetryInboxAdapter.reconstruct(withoutTrace).batch().traceId());
        withoutTrace.remove("correlationId");
        assertThrows(IllegalArgumentException.class,
                () -> MongoTelemetryInboxAdapter.reconstruct(withoutTrace));
    }

    @Test
    void rejectsMalformedBsonInsteadOfReturningAPartialBatch() {
        var malformed = MongoTelemetryInboxAdapter.admittedDocument(
                batch("trace"), TelemetryAdmissionQuality.IN_ORDER,
                new TelemetryInboxPort.Provenance("topic", 0, 0, 100), Instant.parse("2026-09-18T10:00:03Z"));
        malformed.remove("serverReceivedAt");

        var failure = assertThrows(IllegalArgumentException.class,
                () -> MongoTelemetryInboxAdapter.reconstruct(malformed));
        assertEquals("Invalid admitted inbox document", failure.getMessage());
    }

    @Test
    void rejectsRowsThatAreNotExplicitlyAdmitted() {
        var document = MongoTelemetryInboxAdapter.admittedDocument(
                batch("trace"), TelemetryAdmissionQuality.IN_ORDER,
                new TelemetryInboxPort.Provenance("topic", 0, 0, 100), Instant.parse("2026-09-18T10:00:03Z"));
        document.put("status", "QUARANTINED");

        assertThrows(IllegalArgumentException.class,
                () -> MongoTelemetryInboxAdapter.reconstruct(document));
    }

    @Test
    void rejectsEveryRequiredIdentityAndPayloadFieldWhenAbsent() {
        for (String field : List.of("status", "admissionQuality", "schemaVersion", "attemptEventId", "batchId",
                "therapySessionId", "streamId", "capturedAt", "sentAt", "serverReceivedAt",
                "correlationId", "sequence", "payloadSha256", "gazeSamples", "gameplaySamples")) {
            var document = MongoTelemetryInboxAdapter.admittedDocument(
                    batch("trace"), TelemetryAdmissionQuality.IN_ORDER,
                    new TelemetryInboxPort.Provenance("topic", 0, 0, 100),
                    Instant.parse("2026-09-18T10:00:03Z"));
            document.remove(field);
            assertThrows(IllegalArgumentException.class,
                    () -> MongoTelemetryInboxAdapter.reconstruct(document), field);
        }
    }

    private AcceptedTelemetryBatch batch(String traceId) {
        var capturedAt = Instant.parse("2026-09-18T10:00:00Z");
        return new AcceptedTelemetryBatch(
                1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 7,
                capturedAt, capturedAt.plusSeconds(1), capturedAt.plusSeconds(2), UUID.randomUUID(), traceId,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9)),
                List.of(new AcceptedTelemetryBatch.GameplaySample(5, .1, .2, .3, .4, 5, 1.5, .6)),
                "sha256-value");
    }
}

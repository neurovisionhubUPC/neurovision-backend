package com.neurovision.rehab.telemetry.interfaces.events.transform;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.telemetry.application.internal.validation.TelemetryBatchValidator;
import com.neurovision.rehab.telemetry.application.internal.validation.TelemetryValidationPolicy;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import com.neurovision.rehab.telemetry.interfaces.events.contracts.TelemetryBatchAcceptedV1;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Strict inbound Kafka V1 decoder. No event metadata or identity token reaches persistence. */
public final class TelemetryBatchPersistenceEventAssembler {
    private static final Instant POSTGRES_MIN = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant POSTGRES_MAX = Instant.parse("9999-12-31T23:59:59.999999Z");
    private TelemetryBatchPersistenceEventAssembler() {}

    public static Accepted decode(String key, String json, ObjectMapper mapper,
            int maxEventBytes, int maxSamples, Duration futureSkew, Instant now) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > maxEventBytes) {
            throw new IllegalArgumentException("Invalid telemetry event size");
        }
        final TelemetryBatchAcceptedV1 event;
        try {
            event = mapper.readerFor(TelemetryBatchAcceptedV1.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(json);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid telemetry event contract");
        }
        if (event == null || event.schemaVersion() != 1 || event.eventId() == null || event.correlationId() == null
                || event.serverReceivedAt() == null || event.traceId() == null
                || event.gazeSamples() == null || event.gameplaySamples() == null
                || event.gazeSamples().stream().anyMatch(Objects::isNull)
                || event.gameplaySamples().stream().anyMatch(Objects::isNull)
                || (long) event.gazeSamples().size() + event.gameplaySamples().size() > maxSamples
                || !Objects.equals(key, event.therapySessionId() + ":" + event.streamId())
                || event.serverReceivedAt().isAfter(now.plus(futureSkew))) {
            throw new IllegalArgumentException("Invalid telemetry event metadata");
        }
        var batch = new TelemetryBatch(event.schemaVersion(), event.batchId(),
                event.therapySessionId(), event.streamId(), event.sequence(),
                event.capturedAt(), event.sentAt(),
                event.gazeSamples().stream().map(s -> new TelemetryBatch.GazeSample(
                        s.offsetMs(), s.x(), s.y(), s.confidence())).toList(),
                event.gameplaySamples().stream().map(s -> new TelemetryBatch.GameplaySample(
                        s.offsetMs(), s.playerX(), s.playerY(), s.targetX(), s.targetY(),
                        s.score(), s.speed(), s.progress())).toList());
        var validation = new TelemetryBatchValidator().validate(batch,
                new TelemetryValidationPolicy(maxSamples, futureSkew), event.serverReceivedAt());
        if (!validation.isAccepted()) {
            throw new IllegalArgumentException("Invalid telemetry event samples");
        }
        try {
            for (var sample : batch.gazeSamples()) {
                sampleTime(batch.capturedAt(), sample.offsetMs());
            }
            for (var sample : batch.gameplaySamples()) {
                sampleTime(batch.capturedAt(), sample.offsetMs());
            }
            // Exclude publication-attempt metadata; retries may have a new eventId and received time.
            byte[] stable = mapper.writeValueAsBytes(new StablePayload(batch.schemaVersion(), batch.batchId(),
                    batch.therapySessionId(), batch.streamId(), batch.sequence(), batch.capturedAt(),
                    batch.gazeSamples(), batch.gameplaySamples()));
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(stable);
            return new Accepted(batch, event.serverReceivedAt(), HexFormat.of().formatHex(hash));
        } catch (DateTimeException | ArithmeticException invalidTime) {
            throw new IllegalArgumentException("Invalid telemetry sample time");
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException invalidHash) {
            throw new IllegalStateException("Could not fingerprint telemetry batch", invalidHash);
        }
    }

    public static Instant sampleTime(Instant capturedAt, long offsetMs) {
        Instant sampleTime = capturedAt.plusMillis(offsetMs);
        if (sampleTime.isBefore(POSTGRES_MIN) || sampleTime.isAfter(POSTGRES_MAX)) {
            throw new DateTimeException("Sample time is outside PostgreSQL timestamp range");
        }
        return sampleTime;
    }

    public record Accepted(TelemetryBatch batch, Instant serverReceivedAt, String payloadSha256) {}

    private record StablePayload(int schemaVersion, java.util.UUID batchId,
            java.util.UUID therapySessionId, java.util.UUID streamId, long sequence, Instant capturedAt,
            java.util.List<TelemetryBatch.GazeSample> gazeSamples,
            java.util.List<TelemetryBatch.GameplaySample> gameplaySamples) {}
}

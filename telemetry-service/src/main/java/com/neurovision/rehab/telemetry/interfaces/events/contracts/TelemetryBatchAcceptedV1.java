package com.neurovision.rehab.telemetry.interfaces.events.contracts;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Version 1 Kafka wire contract; independent of the browser REST request. */
@JsonPropertyOrder({"schemaVersion", "eventId", "batchId", "therapySessionId", "streamId",
        "sequence", "capturedAt", "sentAt", "serverReceivedAt", "correlationId", "traceId",
        "gazeSamples", "gameplaySamples"})
public record TelemetryBatchAcceptedV1(
        int schemaVersion,
        UUID eventId,
        UUID batchId,
        UUID therapySessionId,
        UUID streamId,
        long sequence,
        Instant capturedAt,
        Instant sentAt,
        Instant serverReceivedAt,
        UUID correlationId,
        String traceId,
        List<GazeSampleV1> gazeSamples,
        List<GameplaySampleV1> gameplaySamples) {

    public TelemetryBatchAcceptedV1 {
        gazeSamples = List.copyOf(gazeSamples);
        gameplaySamples = List.copyOf(gameplaySamples);
    }

    @JsonPropertyOrder({"offsetMs", "x", "y", "confidence"})
    public record GazeSampleV1(long offsetMs, double x, double y, double confidence) {}

    @JsonPropertyOrder({"offsetMs", "playerX", "playerY", "targetX", "targetY", "score", "speed", "progress"})
    public record GameplaySampleV1(long offsetMs, double playerX, double playerY,
            double targetX, double targetY, long score, double speed, double progress) {}
}

package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AcceptedTelemetryBatch(
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
        List<GazeSample> gazeSamples,
        List<GameplaySample> gameplaySamples,
        String payloadSha256) {

    public AcceptedTelemetryBatch {
        gazeSamples = List.copyOf(gazeSamples);
        gameplaySamples = List.copyOf(gameplaySamples);
    }

    public record GazeSample(long offsetMs, double x, double y, double confidence) {}

    public record GameplaySample(long offsetMs, double playerX, double playerY,
            double targetX, double targetY, long score, double speed, double progress) {}
}

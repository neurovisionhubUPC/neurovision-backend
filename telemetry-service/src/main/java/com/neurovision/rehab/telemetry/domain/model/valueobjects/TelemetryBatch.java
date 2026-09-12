package com.neurovision.rehab.telemetry.domain.model.valueobjects;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record TelemetryBatch(
        int schemaVersion,
        UUID batchId,
        UUID therapySessionId,
        UUID streamId,
        long sequence,
        Instant capturedAt,
        Instant sentAt,
        List<GazeSample> gazeSamples,
        List<GameplaySample> gameplaySamples) {

    public TelemetryBatch {
        gazeSamples = immutableCopy(gazeSamples);
        gameplaySamples = immutableCopy(gameplaySamples);
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }

    public record GazeSample(long offsetMs, double x, double y, double confidence) {
    }

    public record GameplaySample(
            long offsetMs,
            double playerX,
            double playerY,
            double targetX,
            double targetY,
            long score,
            double speed,
            double progress) {
    }
}

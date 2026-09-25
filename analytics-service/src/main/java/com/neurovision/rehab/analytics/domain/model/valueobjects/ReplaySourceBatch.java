package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** The bounded, admitted source fields required to present both Replay V1 lanes. */
public record ReplaySourceBatch(int schemaVersion, UUID batchId, UUID therapySessionId, UUID streamId,
        long sequence, Instant capturedAt, List<GazeSample> gazeSamples, List<GameplaySample> gameplaySamples) {
    public ReplaySourceBatch {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(therapySessionId, "therapySessionId");
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        gazeSamples = List.copyOf(Objects.requireNonNull(gazeSamples, "gazeSamples"));
        gameplaySamples = List.copyOf(Objects.requireNonNull(gameplaySamples, "gameplaySamples"));
        if (schemaVersion != 1 || sequence < 0 || gazeSamples.stream().anyMatch(Objects::isNull)
                || gameplaySamples.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Invalid replay source batch");
        }
    }

    public record GazeSample(long offsetMs, double x, double y, double confidence) {
        public GazeSample {
            if (offsetMs < 0 || !normalized(x) || !normalized(y) || !normalized(confidence)) {
                throw new IllegalArgumentException("Invalid observed gaze sample");
            }
        }
    }

    public record GameplaySample(long offsetMs, double playerX, double playerY, double targetX, double targetY,
            long score, double speed, double progress) {
        public GameplaySample {
            if (offsetMs < 0 || !normalized(playerX) || !normalized(playerY) || !normalized(targetX)
                    || !normalized(targetY) || score < 0 || !Double.isFinite(speed) || speed < 0
                    || !normalized(progress)) {
                throw new IllegalArgumentException("Invalid observed gameplay sample");
            }
        }
    }

    private static boolean normalized(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}

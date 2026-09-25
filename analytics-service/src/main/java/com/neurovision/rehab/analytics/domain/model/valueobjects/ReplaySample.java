package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One source-observed sample with stable inbox provenance and no synthetic state. */
public record ReplaySample(ReplayLane lane, UUID batchId, long sequence, int sourceArrayIndex,
        Instant capturedAt, long offsetMs, Instant effectiveTime, Double x, Double y,
        Double confidence, Double playerX, Double playerY, Double targetX, Double targetY,
        Long score, Double speed, Double progress, boolean segmentBreakBefore) {
    public ReplaySample {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(effectiveTime, "effectiveTime");
        if (sequence < 0 || sourceArrayIndex < 0 || offsetMs < 0) throw new IllegalArgumentException("Invalid replay sample provenance");
        if (lane == ReplayLane.GAZE && (x == null || y == null || confidence == null || playerX != null || score != null)
                || lane == ReplayLane.GAMEPLAY && (x != null || y != null || confidence != null || playerX == null || score == null)) {
            throw new IllegalArgumentException("Replay sample does not match its lane");
        }
        if (lane == ReplayLane.GAZE && (!normalized(x) || !normalized(y) || !normalized(confidence))
                || lane == ReplayLane.GAMEPLAY && (!normalized(playerX) || !normalized(playerY)
                        || !normalized(targetX) || !normalized(targetY) || score < 0
                        || !Double.isFinite(speed) || speed < 0 || !normalized(progress))) {
            throw new IllegalArgumentException("Replay sample contains invalid observed values");
        }
    }

    private static boolean normalized(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= 1;
    }
}

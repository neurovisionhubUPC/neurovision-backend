package com.neurovision.rehab.telemetry.interfaces.rest.resource.v1;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({
        "schemaVersion", "batchId", "therapySessionId", "streamId", "sequence",
        "capturedAt", "sentAt", "gazeSamples", "gameplaySamples"
})
public record TelemetryBatchV1(
        int schemaVersion,
        UUID batchId,
        UUID therapySessionId,
        UUID streamId,
        long sequence,
        Instant capturedAt,
        Instant sentAt,
        List<GazeSampleV1> gazeSamples,
        List<GameplaySampleV1> gameplaySamples) {

    public TelemetryBatchV1 {
        gazeSamples = immutableCopy(gazeSamples);
        gameplaySamples = immutableCopy(gameplaySamples);
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }

    @JsonPropertyOrder({ "offsetMs", "x", "y", "confidence" })
    public record GazeSampleV1(long offsetMs, double x, double y, double confidence) {
    }

    @JsonPropertyOrder({
            "offsetMs", "playerX", "playerY", "targetX", "targetY", "score", "speed", "progress"
    })
    public record GameplaySampleV1(
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

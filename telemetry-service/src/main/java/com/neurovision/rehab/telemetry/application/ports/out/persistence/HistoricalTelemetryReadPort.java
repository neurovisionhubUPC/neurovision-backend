package com.neurovision.rehab.telemetry.application.ports.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HistoricalTelemetryReadPort {
    List<Sample> read(UUID sessionId, UUID streamId, Instant from, Instant to,
            Cursor after, int fetchLimit);

    record Cursor(Instant sampleTime, UUID batchId, String kind, int index) {}

    record Sample(Instant sampleTime, UUID batchId, long sequence, String kind,
            int index, long offsetMs, Instant serverReceivedAt,
            Double x, Double y, Double confidence,
            Double playerX, Double playerY, Double targetX, Double targetY,
            Long score, Double speed, Double progress) {
        public Cursor cursor() { return new Cursor(sampleTime, batchId, kind, index); }
    }
}

package com.neurovision.rehab.telemetry.interfaces.rest.resource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TelemetrySamplesPageV1(int contractVersion, List<SampleV1> samples, String nextCursor) {
    public record SampleV1(Instant sampleTime, UUID batchId, long sequenceNumber,
            String kind, int index, long offsetMs, Instant serverReceivedAt,
            Double x, Double y, Double confidence, Double playerX, Double playerY,
            Double targetX, Double targetY, Long score, Double speed, Double progress) {}
}

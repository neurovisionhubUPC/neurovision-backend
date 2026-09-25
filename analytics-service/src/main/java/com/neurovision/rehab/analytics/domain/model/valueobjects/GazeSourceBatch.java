package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Minimal admitted inbox data needed to page observed gaze samples. */
public record GazeSourceBatch(int schemaVersion, UUID therapySessionId, UUID streamId,
        long sequence, Instant capturedAt, List<Sample> gazeSamples) {
    public GazeSourceBatch {
        Objects.requireNonNull(therapySessionId, "therapySessionId");
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(gazeSamples, "gazeSamples");
        gazeSamples = List.copyOf(gazeSamples);
        if (schemaVersion != 1 || sequence < 0 || gazeSamples.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Invalid gaze source batch");
        }
        long previousOffset = -1;
        for (var sample : gazeSamples) {
            if (sample.offsetMs() < previousOffset) {
                throw new IllegalArgumentException("Gaze samples are not in source array time order");
            }
            previousOffset = sample.offsetMs();
        }
    }

    public record Sample(long offsetMs, double x, double y, double confidence) {
        public Sample {
            if (offsetMs < 0 || !normalized(x) || !normalized(y) || !normalized(confidence)) {
                throw new IllegalArgumentException("Invalid observed gaze sample");
            }
        }

        private static boolean normalized(double value) {
            return Double.isFinite(value) && value >= 0 && value <= 1;
        }
    }
}

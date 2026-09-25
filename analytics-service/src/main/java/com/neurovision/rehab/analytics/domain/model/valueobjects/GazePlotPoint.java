package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.time.Instant;
import java.util.Objects;

/** One admitted source gaze point; confidence is source metadata, not a filter. */
public record GazePlotPoint(
        long sequence,
        int gazeSampleIndex,
        Instant effectiveTime,
        double x,
        double y,
        double confidence,
        boolean segmentBreakBefore) {
    public GazePlotPoint {
        Objects.requireNonNull(effectiveTime, "effectiveTime");
        if (sequence < 0 || gazeSampleIndex < 0 || !normalized(x) || !normalized(y) || !normalized(confidence)) {
            throw new IllegalArgumentException("Invalid observed gaze point");
        }
    }

    private static boolean normalized(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}

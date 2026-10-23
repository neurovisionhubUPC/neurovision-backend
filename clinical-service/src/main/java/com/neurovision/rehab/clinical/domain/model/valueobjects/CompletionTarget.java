package com.neurovision.rehab.clinical.domain.model.valueobjects;

import java.util.Objects;

/** Prescribed technical fulfillment, not clinical quality or telemetry durability. */
public record CompletionTarget(Criterion criterion, long target) {
    public enum Criterion { ACTIVE_TIME, REPETITIONS }
    public CompletionTarget {
        Objects.requireNonNull(criterion);
        if (target < 1 || target > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid completion target");
    }
    /** ACTIVE_TIME targets are seconds; accounting retains milliseconds between events. */
    public boolean reached(SessionActivityProgress progress) {
        return switch (criterion) {
            case ACTIVE_TIME -> progress.activeMilliseconds() >= target * 1000L;
            case REPETITIONS -> progress.completedRepetitions() >= target;
        };
    }
}

package com.neurovision.rehab.analytics.domain.model.valueobjects;

import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import java.util.List;
import java.util.UUID;

/** A final producer report, not a reconstruction or certification of gaze samples. */
public record SystematicExplorationTrialResult(UUID therapySessionId, String trialId, DataOrigin dataOrigin,
        Outcome outcome, long elapsedMs, List<Visit> visits) {
    public enum Outcome { COMPLETED, TIMED_OUT, INTERRUPTED }

    /** Enum order is the fixed traversal order, never an arbitrary region editor. */
    public enum Quadrant {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

        public static Quadrant fromNormalized(double x, double y) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1)
                throw new IllegalArgumentException("Invalid Systematic Exploration position");
            return values()[(y >= 0.5 ? 2 : 0) + (x >= 0.5 ? 1 : 0)];
        }
    }

    /** Both times are relative to activation of this quadrant; no wall-clock timestamp is needed. */
    public record Visit(Quadrant quadrant, long timeToVisitMs, long confirmedDwellMs) {
        public Visit {
            if (quadrant == null || timeToVisitMs < 0 || confirmedDwellMs < 0 || confirmedDwellMs > timeToVisitMs)
                throw invalid();
        }
    }

    public SystematicExplorationTrialResult {
        if (therapySessionId == null || trialId == null || trialId.isBlank() || dataOrigin == null
                || outcome == null || elapsedMs < 0 || visits == null || visits.size() > 4)
            throw invalid();
        // Reject null entries with a fixed diagnostic, rather than leaking input through a nested exception.
        for (int i = 0; i < visits.size(); i++) {
            if (visits.get(i) == null || visits.get(i).quadrant() != Quadrant.values()[i]) throw invalid();
        }
        visits = List.copyOf(visits);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Systematic Exploration trial result");
    }

    @Override public String toString() { return "SystematicExplorationTrialResult[redacted]"; }
}

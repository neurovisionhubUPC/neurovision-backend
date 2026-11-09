package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.UUID;

/** Frozen policy supplied by the caller; this value performs no Clinical lookup or authorization. */
public record SystematicExplorationTrialContext(UUID therapySessionId, UUID configurationId,
        long configurationVersion, long minimumFixationMs, long maximumStimulusDurationMs) {
    public SystematicExplorationTrialContext {
        if (therapySessionId == null || configurationId == null || configurationVersion < 1
                || minimumFixationMs < 300 || minimumFixationMs > 2000
                || maximumStimulusDurationMs < 2000 || maximumStimulusDurationMs > 10000
                || minimumFixationMs > maximumStimulusDurationMs)
            throw new IllegalArgumentException("Invalid Systematic Exploration trial context");
    }

    public void validate(SystematicExplorationTrialResult result) {
        if (result == null || !therapySessionId.equals(result.therapySessionId())) throw invalid();
        long confirmedDuration = 0;
        for (var visit : result.visits()) {
            if (visit.confirmedDwellMs() < minimumFixationMs || visit.timeToVisitMs() > maximumStimulusDurationMs)
                throw invalid();
            // At most four visits of at most 10000 ms: the sum is bounded before subtraction.
            confirmedDuration += visit.timeToVisitMs();
        }
        if (result.elapsedMs() < confirmedDuration) throw invalid();
        long remaining = result.elapsedMs() - confirmedDuration;
        boolean valid = switch (result.outcome()) {
            case COMPLETED -> result.visits().size() == 4 && remaining == 0;
            case TIMED_OUT -> result.visits().size() < 4 && remaining == maximumStimulusDurationMs;
            case INTERRUPTED -> result.visits().size() < 4 && remaining < maximumStimulusDurationMs;
        };
        if (!valid) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Systematic Exploration trial result");
    }

    @Override public String toString() { return "SystematicExplorationTrialContext[redacted]"; }
}

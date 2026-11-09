package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.UUID;

/** Supplied frozen Fixation policy; performs no configuration lookup or authorization. */
public record SustainedFixationTrialContext(UUID therapySessionId, UUID configurationId, long configurationVersion,
        long minimumFixationMs, long maximumFixationMs) {
    public SustainedFixationTrialContext {
        if (therapySessionId == null || configurationId == null || configurationVersion < 1
                || minimumFixationMs < 300 || minimumFixationMs > 3000
                || maximumFixationMs < 500 || maximumFixationMs > 5000 || maximumFixationMs < minimumFixationMs)
            throw new IllegalArgumentException("Invalid Sustained Fixation trial context");
    }

    public void validate(SustainedFixationTrialResult result) {
        if (result == null || !therapySessionId.equals(result.therapySessionId()) || result.elapsedMs() > maximumFixationMs)
            throw new IllegalArgumentException("Invalid Sustained Fixation trial result");

        boolean completed = result.longestContinuousInsideMs() >= minimumFixationMs;
        boolean atDeadline = result.elapsedMs() == maximumFixationMs;
        boolean consistent = switch (result.outcome()) {
            case SUCCESS -> completed;
            case TIMEOUT -> !completed && atDeadline;
            case INTERRUPTED -> !completed && !atDeadline;
        };
        if (!consistent) throw new IllegalArgumentException("Invalid Sustained Fixation trial outcome");
    }

    @Override public String toString() { return "SustainedFixationTrialContext[redacted]"; }
}

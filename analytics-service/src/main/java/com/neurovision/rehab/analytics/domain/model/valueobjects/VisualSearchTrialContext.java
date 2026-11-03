package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.UUID;

/** Supplied frozen Visual Search policy; this value does not fetch or authorize a Clinical session. */
public record VisualSearchTrialContext(UUID therapySessionId, UUID configurationId, long configurationVersion,
        long minimumFixationMs, long maximumSearchTimeMs) {
    public VisualSearchTrialContext {
        if (therapySessionId == null || configurationId == null || configurationVersion < 1
                || minimumFixationMs < 1 || maximumSearchTimeMs < minimumFixationMs)
            throw new IllegalArgumentException("Invalid Visual Search trial context");
    }

    public void validate(VisualSearchTrialResult result) {
        if (result == null || !therapySessionId.equals(result.therapySessionId()))
            throw new IllegalArgumentException("Invalid Visual Search trial result");
        boolean valid = switch (result.outcome()) {
            case SUCCESS -> result.confirmedDwellMs() >= minimumFixationMs
                    && result.searchDurationMs() <= maximumSearchTimeMs;
            case TIMEOUT -> result.searchDurationMs() >= maximumSearchTimeMs;
            case INTERRUPTED -> result.searchDurationMs() < maximumSearchTimeMs;
        };
        if (!valid) throw new IllegalArgumentException("Invalid Visual Search trial result");
    }

    @Override public String toString() { return "VisualSearchTrialContext[redacted]"; }
}

package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.UUID;

/** Supplied frozen stimulus policy; not a Clinical authorization or configuration lookup. */
public record CompensatorySaccadeTrialContext(UUID therapySessionId, UUID configurationId, long configurationVersion,
        long minimumFixationMs, long maximumStimulusDurationMs) {
    public CompensatorySaccadeTrialContext {
        try {
            new VisualSearchTrialContext(therapySessionId, configurationId, configurationVersion,
                    minimumFixationMs, maximumStimulusDurationMs);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Compensatory Saccade trial context");
        }
    }

    public void validate(CompensatorySaccadeTrialResult result) {
        try {
            new VisualSearchTrialContext(therapySessionId, configurationId, configurationVersion,
                    minimumFixationMs, maximumStimulusDurationMs)
                    .validate(result == null ? null : result.asTerminalResult());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Compensatory Saccade trial result");
        }
    }

    @Override public String toString() { return "CompensatorySaccadeTrialContext[redacted]"; }
}

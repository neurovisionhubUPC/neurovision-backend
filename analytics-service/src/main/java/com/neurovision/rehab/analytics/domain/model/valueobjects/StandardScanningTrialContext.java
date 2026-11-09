package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.UUID;

/** Supplied frozen STANDARD_SCANNING policy, not a Clinical authorization or configuration lookup. */
public record StandardScanningTrialContext(UUID therapySessionId, UUID configurationId, long configurationVersion,
        long minimumFixationMs, long maximumStimulusDurationMs) {
    public StandardScanningTrialContext {
        try {
            new VisualSearchTrialContext(therapySessionId, configurationId, configurationVersion,
                    minimumFixationMs, maximumStimulusDurationMs);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Standard Scanning trial context");
        }
    }

    public void validate(StandardScanningTrialResult result) {
        if (result == null) throw new IllegalArgumentException("Invalid Standard Scanning trial result");
        try {
            new VisualSearchTrialContext(therapySessionId, configurationId, configurationVersion,
                    minimumFixationMs, maximumStimulusDurationMs).validate(new VisualSearchTrialResult(
                            result.schemaVersion(), result.therapySessionId(), result.trialId(), result.outcome(),
                            result.localizationDurationMs(), result.confirmedDwellMs(), result.dataOrigin()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Standard Scanning trial result");
        }
    }

    @Override public String toString() { return "StandardScanningTrialContext[redacted]"; }
}

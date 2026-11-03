package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.UUID;

/** One producer-reported terminal result, not proof of gaze accuracy or session completeness. */
public record VisualSearchTrialResult(int schemaVersion, UUID therapySessionId, String trialId,
        Outcome outcome, long searchDurationMs, Long confirmedDwellMs, DataOrigin dataOrigin) {
    public static final int SCHEMA_VERSION = 1;
    public enum Outcome { SUCCESS, TIMEOUT, INTERRUPTED }
    public enum DataOrigin { SYNTHETIC, PRODUCER_REPORTED }

    public VisualSearchTrialResult {
        if (schemaVersion != SCHEMA_VERSION || therapySessionId == null || trialId == null || trialId.isBlank()
                || outcome == null || dataOrigin == null || searchDurationMs < 0)
            throw new IllegalArgumentException("Invalid Visual Search trial result");
        if (outcome == Outcome.SUCCESS) {
            if (confirmedDwellMs == null || confirmedDwellMs < 0 || confirmedDwellMs > searchDurationMs)
                throw new IllegalArgumentException("Invalid Visual Search trial result");
        } else if (confirmedDwellMs != null) {
            throw new IllegalArgumentException("Invalid Visual Search trial result");
        }
    }

    @Override public String toString() { return "VisualSearchTrialResult[redacted]"; }
}

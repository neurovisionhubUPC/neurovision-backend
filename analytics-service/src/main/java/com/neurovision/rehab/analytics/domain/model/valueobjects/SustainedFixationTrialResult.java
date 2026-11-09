package com.neurovision.rehab.analytics.domain.model.valueobjects;

import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.Outcome;
import java.util.UUID;

/** One producer-reported terminal attempt, not independent verification of gaze. */
public record SustainedFixationTrialResult(int schemaVersion, UUID therapySessionId, String trialId,
        Outcome outcome, long elapsedMs, long insideTargetMs, long longestContinuousInsideMs, DataOrigin dataOrigin) {
    public static final int SCHEMA_VERSION = 1;

    public SustainedFixationTrialResult {
        if (schemaVersion != SCHEMA_VERSION || therapySessionId == null || trialId == null || trialId.isBlank()
                || outcome == null || dataOrigin == null || longestContinuousInsideMs < 0
                || longestContinuousInsideMs > insideTargetMs || insideTargetMs > elapsedMs)
            throw new IllegalArgumentException("Invalid Sustained Fixation trial result");
    }

    @Override public String toString() { return "SustainedFixationTrialResult[redacted]"; }
}

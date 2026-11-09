package com.neurovision.rehab.analytics.domain.model.valueobjects;

import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.Outcome;
import java.util.UUID;

/** One visible target presentation; producer confirmation is not a physiological saccade measurement. */
public record CompensatorySaccadeTrialResult(UUID therapySessionId, String trialId, DataOrigin dataOrigin,
        Outcome outcome, long elapsedMs, Long confirmedDwellMs) {
    public CompensatorySaccadeTrialResult {
        try {
            new VisualSearchTrialResult(VisualSearchTrialResult.SCHEMA_VERSION, therapySessionId, trialId,
                    outcome, elapsedMs, confirmedDwellMs, dataOrigin);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Compensatory Saccade trial result");
        }
    }

    VisualSearchTrialResult asTerminalResult() {
        return new VisualSearchTrialResult(VisualSearchTrialResult.SCHEMA_VERSION, therapySessionId, trialId,
                outcome, elapsedMs, confirmedDwellMs, dataOrigin);
    }

    @Override public String toString() { return "CompensatorySaccadeTrialResult[redacted]"; }
}

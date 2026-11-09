package com.neurovision.rehab.analytics.domain.model.valueobjects;

import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.Outcome;
import java.util.UUID;

/** One visible stimulus presentation; reported detection is not independent gaze verification. */
public record StandardScanningTrialResult(int schemaVersion, UUID therapySessionId, String trialId,
        Outcome outcome, long localizationDurationMs, Long confirmedDwellMs, DataOrigin dataOrigin) {
    public static final int SCHEMA_VERSION = 1;

    public StandardScanningTrialResult {
        // Terminal-result shape and provenance semantics are identical to the existing pure core.
        try {
            new VisualSearchTrialResult(schemaVersion, therapySessionId, trialId, outcome,
                    localizationDurationMs, confirmedDwellMs, dataOrigin);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Standard Scanning trial result");
        }
    }

    @Override public String toString() { return "StandardScanningTrialResult[redacted]"; }
}

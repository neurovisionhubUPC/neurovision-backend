package com.neurovision.rehab.analytics.domain.services;

import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reuses the pure terminal-result reduction without changing Visual Search or its public outputs. */
public final class StandardScanningTrialMetricsCalculator {
    public static final String CALCULATION_VERSION = "standard-scanning-trial-v1";

    public Summary calculate(StandardScanningTrialContext context, DataOrigin origin,
            List<StandardScanningTrialResult> results) {
        if (context == null || origin == null || results == null)
            throw new IllegalArgumentException("Invalid Standard Scanning trial calculation");
        for (var result : results) context.validate(result);
        try {
            var summary = new VisualSearchTrialMetricsCalculator().calculate(new VisualSearchTrialContext(
                    context.therapySessionId(), context.configurationId(), context.configurationVersion(),
                    context.minimumFixationMs(), context.maximumStimulusDurationMs()), origin,
                    results.stream().map(result -> new VisualSearchTrialResult(result.schemaVersion(),
                            result.therapySessionId(), result.trialId(), result.outcome(), result.localizationDurationMs(),
                            result.confirmedDwellMs(), result.dataOrigin())).toList());
            return new Summary(CALCULATION_VERSION, summary.therapySessionId(), summary.configurationId(),
                    summary.configurationVersion(), summary.dataOrigin(), summary.reportedTrialCount(), summary.hitCount(),
                    summary.omissionCount(), summary.interruptedCount(), summary.eligibleSearchCount(),
                    summary.medianSearchDurationMs());
        } catch (IllegalArgumentException invalid) {
            if ("Conflicting Visual Search trial result".equals(invalid.getMessage()))
                throw new IllegalArgumentException("Conflicting Standard Scanning trial result");
            throw new IllegalArgumentException("Invalid Standard Scanning trial calculation");
        }
    }

    /** Observed distinct outcomes only; no presented total, clinical success or session completeness claim. */
    public record Summary(String calculationVersion, UUID therapySessionId, UUID configurationId,
            long configurationVersion, DataOrigin dataOrigin, long reportedTrialCount, long detectedTargetCount,
            long omissionCount, long interruptedCount, long eligibleLocalizationCount,
            Optional<BigDecimal> medianLocalizationDurationMs) {
        @Override public String toString() { return "StandardScanningTrialMetrics[redacted]"; }
    }
}

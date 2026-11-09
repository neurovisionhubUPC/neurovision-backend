package com.neurovision.rehab.analytics.domain.services;

import com.neurovision.rehab.analytics.domain.model.valueobjects.CompensatorySaccadeTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.CompensatorySaccadeTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Pure observed-outcome reduction. Time to target includes the confirmation dwell. */
public final class CompensatorySaccadeTrialMetricsCalculator {
    public static final String CALCULATION_VERSION = "compensatory-saccade-trial-v1";

    public Summary calculate(CompensatorySaccadeTrialContext context, DataOrigin origin,
            List<CompensatorySaccadeTrialResult> results) {
        if (context == null || origin == null || results == null) throw invalid();
        try {
            // Reuse the same terminal-result reduction as Standard Scanning, not its report or transport.
            var summary = new VisualSearchTrialMetricsCalculator().calculate(new VisualSearchTrialContext(
                    context.therapySessionId(), context.configurationId(), context.configurationVersion(),
                    context.minimumFixationMs(), context.maximumStimulusDurationMs()), origin,
                    results.stream().map(result -> result == null ? null : new VisualSearchTrialResult(
                            VisualSearchTrialResult.SCHEMA_VERSION, result.therapySessionId(), result.trialId(),
                            result.outcome(), result.elapsedMs(), result.confirmedDwellMs(), result.dataOrigin())).toList());
            return new Summary(CALCULATION_VERSION, summary.therapySessionId(), summary.configurationId(),
                    summary.configurationVersion(), summary.dataOrigin(), summary.reportedTrialCount(), summary.hitCount(),
                    summary.medianSearchDurationMs());
        } catch (IllegalArgumentException invalid) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Compensatory Saccade trial calculation");
    }

    /** Counts describe supplied attempts only; an empty median never means zero milliseconds. */
    public record Summary(String calculationVersion, UUID therapySessionId, UUID configurationId,
            long configurationVersion, DataOrigin dataOrigin, long reportedTrialCount, long reachedTargetCount,
            Optional<BigDecimal> medianTimeToTargetMs) {
        @Override public String toString() { return "CompensatorySaccadeTrialMetrics[redacted]"; }
    }
}

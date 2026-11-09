package com.neurovision.rehab.analytics.domain.services;

import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.Outcome;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/** Pure aggregation of reported terminal attempts, with no durable deduplication or I/O. */
public final class SustainedFixationTrialMetricsCalculator {
    public static final String CALCULATION_VERSION = "sustained-fixation-trial-v1";

    public Summary calculate(SustainedFixationTrialContext context, DataOrigin origin,
            List<SustainedFixationTrialResult> results) {
        if (context == null || origin == null || results == null)
            throw new IllegalArgumentException("Invalid Sustained Fixation trial calculation");

        var unique = new HashMap<String, SustainedFixationTrialResult>();
        long totalInsideTargetMs = 0;
        long completedTargetCount = 0;
        for (var result : results) {
            context.validate(result);
            if (result.dataOrigin() != origin)
                throw new IllegalArgumentException("Invalid Sustained Fixation trial data origin");
            var previous = unique.putIfAbsent(result.trialId(), result);
            if (previous != null) {
                if (!previous.equals(result))
                    throw new IllegalArgumentException("Conflicting Sustained Fixation trial result");
                continue;
            }
            totalInsideTargetMs += result.insideTargetMs();
            if (result.outcome() == Outcome.SUCCESS) completedTargetCount++;
        }

        return new Summary(CALCULATION_VERSION, context.therapySessionId(), context.configurationId(),
                context.configurationVersion(), origin, unique.size(), totalInsideTargetMs, completedTargetCount);
    }

    /** Totals describe supplied distinct attempts only; zero reported attempts means no supplied evidence. */
    public record Summary(String calculationVersion, UUID therapySessionId, UUID configurationId,
            long configurationVersion, DataOrigin dataOrigin, long reportedTrialCount,
            long totalInsideTargetMs, long completedTargetCount) {
        @Override public String toString() { return "SustainedFixationTrialMetrics[redacted]"; }
    }
}

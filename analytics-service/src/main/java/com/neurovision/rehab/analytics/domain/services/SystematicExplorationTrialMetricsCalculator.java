package com.neurovision.rehab.analytics.domain.services;

import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult.Outcome;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/** Pure per-traversal reduction; no transport, durable deduplication or clinical interpretation. */
public final class SystematicExplorationTrialMetricsCalculator {
    public static final String CALCULATION_VERSION = "systematic-exploration-trial-v1";

    public Summary calculate(SystematicExplorationTrialContext context, DataOrigin origin,
            List<SystematicExplorationTrialResult> results) {
        if (context == null || origin == null || results == null)
            throw new IllegalArgumentException("Invalid Systematic Exploration trial calculation");
        var unique = new TreeMap<String, SystematicExplorationTrialResult>();
        for (var result : results) {
            context.validate(result);
            if (result.dataOrigin() != origin)
                throw new IllegalArgumentException("Invalid Systematic Exploration trial data origin");
            var previous = unique.putIfAbsent(result.trialId(), result);
            if (previous != null && !previous.equals(result))
                throw new IllegalArgumentException("Conflicting Systematic Exploration trial result");
        }
        var metrics = unique.values().stream().map(result -> new TraversalMetrics(result.trialId(), result.outcome(),
                result.visits().size(), result.visits().size() * 25, result.elapsedMs())).toList();
        return new Summary(CALCULATION_VERSION, context.therapySessionId(), context.configurationId(),
                context.configurationVersion(), origin, unique.size(), metrics);
    }

    /** Coverage concerns the four prescribed screen regions, not recovery of the visual field. */
    public record TraversalMetrics(String trialId, Outcome outcome, int visitedRegionCount,
            int coveragePercent, long elapsedMs) {
        @Override public String toString() { return "SystematicExplorationTraversalMetrics[redacted]"; }
    }

    /** Empty input has no traversal metrics; it must not manufacture a zero-valued attempt. */
    public record Summary(String calculationVersion, UUID therapySessionId, UUID configurationId,
            long configurationVersion, DataOrigin dataOrigin, long reportedTrialCount, List<TraversalMetrics> traversals) {
        public Summary { traversals = List.copyOf(traversals); }
        @Override public String toString() { return "SystematicExplorationTrialMetrics[redacted]"; }
    }
}

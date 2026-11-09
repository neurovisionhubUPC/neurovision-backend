package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neurovision.rehab.analytics.domain.services.SystematicExplorationTrialMetricsCalculator.Summary;
import java.util.List;

/** Observed traversal metrics only; coverage is not clinical recovery or session completeness. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SystematicExplorationTrialSummaryResponse(String calculationVersion, String therapySessionId,
        String configurationId, long configurationVersion, String dataOrigin, long reportedTrialCount,
        List<Traversal> traversals) {
    public SystematicExplorationTrialSummaryResponse { traversals = List.copyOf(traversals); }

    static SystematicExplorationTrialSummaryResponse from(Summary summary) {
        return new SystematicExplorationTrialSummaryResponse(summary.calculationVersion(),summary.therapySessionId().toString(),
                summary.configurationId().toString(),summary.configurationVersion(),summary.dataOrigin().name(),
                summary.reportedTrialCount(),summary.traversals().stream().map(t -> new Traversal(t.trialId(),
                        t.outcome().name(),t.visitedRegionCount(),t.coveragePercent(),t.elapsedMs())).toList());
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Traversal(String trialId, String outcome, int visitedRegionCount, int coveragePercent, long elapsedMs) {
        @Override public String toString() { return "SystematicExplorationTraversalResponse[redacted]"; }
    }

    @Override public String toString() { return "SystematicExplorationTrialSummaryResponse[redacted]"; }
}

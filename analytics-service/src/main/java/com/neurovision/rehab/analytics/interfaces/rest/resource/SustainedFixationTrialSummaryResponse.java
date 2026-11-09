package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neurovision.rehab.analytics.domain.services.SustainedFixationTrialMetricsCalculator.Summary;

/** Observed final results only; no continuous-dwell, efficacy or session completeness claim. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SustainedFixationTrialSummaryResponse(String calculationVersion, String therapySessionId,
        String configurationId, long configurationVersion, String dataOrigin, long reportedTrialCount,
        long totalInsideTargetMs, long completedTargetCount) {
    static SustainedFixationTrialSummaryResponse from(Summary summary) {
        return new SustainedFixationTrialSummaryResponse(summary.calculationVersion(),summary.therapySessionId().toString(),
                summary.configurationId().toString(),summary.configurationVersion(),summary.dataOrigin().name(),
                summary.reportedTrialCount(),summary.totalInsideTargetMs(),summary.completedTargetCount());
    }
    @Override public String toString() { return "SustainedFixationTrialSummaryResponse[redacted]"; }
}

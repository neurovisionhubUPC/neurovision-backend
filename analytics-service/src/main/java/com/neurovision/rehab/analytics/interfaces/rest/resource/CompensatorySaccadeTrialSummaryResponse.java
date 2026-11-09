package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neurovision.rehab.analytics.domain.services.CompensatorySaccadeTrialMetricsCalculator.Summary;
import java.math.BigDecimal;

/** Observed final results only; no regional or completeness claims. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CompensatorySaccadeTrialSummaryResponse(String calculationVersion, String therapySessionId,
        String configurationId, long configurationVersion, String dataOrigin, long reportedTrialCount,
        long reachedTargetCount, BigDecimal medianTimeToTargetMs) {
    static CompensatorySaccadeTrialSummaryResponse from(Summary summary) {
        return new CompensatorySaccadeTrialSummaryResponse(summary.calculationVersion(), summary.therapySessionId().toString(),
                summary.configurationId().toString(), summary.configurationVersion(), summary.dataOrigin().name(),
                summary.reportedTrialCount(), summary.reachedTargetCount(), summary.medianTimeToTargetMs().orElse(null));
    }

    @Override public String toString() { return "CompensatorySaccadeTrialSummaryResponse[redacted]"; }
}

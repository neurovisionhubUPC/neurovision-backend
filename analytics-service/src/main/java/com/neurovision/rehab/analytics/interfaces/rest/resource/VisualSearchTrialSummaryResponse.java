package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neurovision.rehab.analytics.domain.services.VisualSearchTrialMetricsCalculator.Summary;
import java.math.BigDecimal;

/** Observed final results only; no presentation count, selection errors or completeness claim. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VisualSearchTrialSummaryResponse(String calculationVersion, String therapySessionId,
        String configurationId, long configurationVersion, String dataOrigin, long reportedTrialCount,
        long hitCount, long omissionCount, long interruptedCount, long eligibleSearchCount,
        BigDecimal medianSearchDurationMs) {
    static VisualSearchTrialSummaryResponse from(Summary summary) {
        return new VisualSearchTrialSummaryResponse(summary.calculationVersion(),summary.therapySessionId().toString(),
                summary.configurationId().toString(),summary.configurationVersion(),summary.dataOrigin().name(),
                summary.reportedTrialCount(),summary.hitCount(),summary.omissionCount(),summary.interruptedCount(),
                summary.eligibleSearchCount(),summary.medianSearchDurationMs().orElse(null));
    }
    @Override public String toString() { return "VisualSearchTrialSummaryResponse[redacted]"; }
}

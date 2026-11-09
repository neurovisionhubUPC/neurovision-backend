package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neurovision.rehab.analytics.domain.services.StandardScanningTrialMetricsCalculator.Summary;
import java.math.BigDecimal;

/** Observed final results only; no presentation count, selection errors or completeness claim. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record StandardScanningTrialSummaryResponse(String calculationVersion, String therapySessionId,
        String configurationId, long configurationVersion, String dataOrigin, long reportedTrialCount,
        long detectedTargetCount, long omissionCount, long interruptedCount, long eligibleLocalizationCount,
        BigDecimal medianLocalizationDurationMs) {
    static StandardScanningTrialSummaryResponse from(Summary summary) {
        return new StandardScanningTrialSummaryResponse(summary.calculationVersion(),summary.therapySessionId().toString(),
                summary.configurationId().toString(),summary.configurationVersion(),summary.dataOrigin().name(),
                summary.reportedTrialCount(),summary.detectedTargetCount(),summary.omissionCount(),summary.interruptedCount(),
                summary.eligibleLocalizationCount(),summary.medianLocalizationDurationMs().orElse(null));
    }
    @Override public String toString() { return "StandardScanningTrialSummaryResponse[redacted]"; }
}

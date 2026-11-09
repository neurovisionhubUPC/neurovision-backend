package com.neurovision.rehab.clinical.application.ports.out.integration;

import java.math.BigDecimal;
import java.util.*;

/** An explicitly selected observed summary, independent of baseline gaze analytics. */
@FunctionalInterface
public interface SessionReportStandardScanningPort {
    Summary observe(UUID therapySessionId, Origin origin, String originalAccessToken);

    enum Origin { SYNTHETIC, PRODUCER_REPORTED }

    /** Distinct reported final results, not presentation totals or clinical efficacy. No recalculation. */
    record Summary(String calculationVersion, UUID therapySessionId, UUID configurationId,
            long configurationVersion, Origin dataOrigin, long reportedTrialCount, long detectedTargetCount,
            long omissionCount, long interruptedCount, long eligibleLocalizationCount, BigDecimal medianLocalizationDurationMs) {
        public Summary {
            Objects.requireNonNull(therapySessionId); Objects.requireNonNull(configurationId); Objects.requireNonNull(dataOrigin);
            if (!"standard-scanning-trial-v1".equals(calculationVersion) || configurationVersion < 1
                    || reportedTrialCount < 0 || detectedTargetCount < 0 || omissionCount < 0 || interruptedCount < 0
                    || detectedTargetCount > reportedTrialCount || omissionCount > reportedTrialCount - detectedTargetCount
                    || interruptedCount != reportedTrialCount - detectedTargetCount - omissionCount || eligibleLocalizationCount != detectedTargetCount
                    || (detectedTargetCount == 0) != (medianLocalizationDurationMs == null)) throw invalid();
            if (medianLocalizationDurationMs != null) {
                var median = medianLocalizationDurationMs.stripTrailingZeros();
                // Integer producer durations yield integer or half-millisecond medians only.
                if (median.signum() < 0 || median.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
                        || median.scale() > 1 || (median.scale() == 1 && !median.unscaledValue().mod(java.math.BigInteger.valueOf(5)).equals(java.math.BigInteger.ZERO)))
                    throw invalid();
            }
        }
        private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid report standard scanning values"); }
        @Override public String toString() { return "ReportStandardScanningSummary[redacted]"; }
    }
}

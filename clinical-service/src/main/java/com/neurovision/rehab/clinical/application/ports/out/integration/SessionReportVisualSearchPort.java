package com.neurovision.rehab.clinical.application.ports.out.integration;

import java.math.BigDecimal;
import java.util.*;

/** An explicitly selected observed summary, independent of baseline gaze analytics. */
@FunctionalInterface
public interface SessionReportVisualSearchPort {
    Summary observe(UUID therapySessionId, Origin origin, String originalAccessToken);

    enum Origin { SYNTHETIC, PRODUCER_REPORTED }

    /** Distinct reported final results, not presentation totals or clinical efficacy. No recalculation. */
    record Summary(String calculationVersion, UUID therapySessionId, UUID configurationId,
            long configurationVersion, Origin dataOrigin, long reportedTrialCount, long hitCount,
            long omissionCount, long interruptedCount, long eligibleSearchCount, BigDecimal medianSearchDurationMs) {
        public Summary {
            Objects.requireNonNull(therapySessionId); Objects.requireNonNull(configurationId); Objects.requireNonNull(dataOrigin);
            if (!"visual-search-trial-v1".equals(calculationVersion) || configurationVersion < 1
                    || reportedTrialCount < 0 || hitCount < 0 || omissionCount < 0 || interruptedCount < 0
                    || hitCount > reportedTrialCount || omissionCount > reportedTrialCount - hitCount
                    || interruptedCount != reportedTrialCount - hitCount - omissionCount || eligibleSearchCount != hitCount
                    || (hitCount == 0) != (medianSearchDurationMs == null)) throw invalid();
            if (medianSearchDurationMs != null) {
                var median = medianSearchDurationMs.stripTrailingZeros();
                // Integer producer durations yield integer or half-millisecond medians only.
                if (median.signum() < 0 || median.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
                        || median.scale() > 1 || (median.scale() == 1 && !median.unscaledValue().mod(java.math.BigInteger.valueOf(5)).equals(java.math.BigInteger.ZERO)))
                    throw invalid();
            }
        }
        private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid report visual search values"); }
        @Override public String toString() { return "ReportVisualSearchSummary[redacted]"; }
    }
}

package com.neurovision.rehab.clinical.application.ports.out.integration;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Optional observed trial summary, separate from baseline analytical evidence. */
@FunctionalInterface
public interface SessionReportCompensatorySaccadePort {
    Summary observe(UUID therapySessionId, Origin origin, String originalAccessToken);

    enum Origin { SYNTHETIC, PRODUCER_REPORTED }

    record Summary(String calculationVersion, UUID therapySessionId, UUID configurationId,
            long configurationVersion, Origin dataOrigin, long reportedTrialCount,
            long reachedTargetCount, BigDecimal medianTimeToTargetMs) {
        public Summary {
            Objects.requireNonNull(therapySessionId);
            Objects.requireNonNull(configurationId);
            Objects.requireNonNull(dataOrigin);
            if (!"compensatory-saccade-trial-v1".equals(calculationVersion) || configurationVersion < 1
                    || reportedTrialCount < 0 || reportedTrialCount > 1000
                    || reachedTargetCount < 0 || reachedTargetCount > reportedTrialCount
                    || (reachedTargetCount == 0) != (medianTimeToTargetMs == null)
                    || (medianTimeToTargetMs != null && !validMedian(medianTimeToTargetMs)))
                throw new IllegalArgumentException("Invalid report compensatory saccade values");
        }
        private static boolean validMedian(BigDecimal median) {
            return median.compareTo(BigDecimal.valueOf(300)) >= 0
                    && median.compareTo(BigDecimal.valueOf(10000)) <= 0
                    && median.stripTrailingZeros().scale() <= 1
                    && median.remainder(BigDecimal.valueOf(0.5)).signum() == 0;
        }
        @Override public String toString() { return "ReportCompensatorySaccadeSummary[redacted]"; }
    }
}

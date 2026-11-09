package com.neurovision.rehab.clinical.application.ports.out.integration;

import java.util.*;

/** An explicitly selected observed summary, independent of baseline gaze analytics. */
@FunctionalInterface
public interface SessionReportSustainedFixationPort {
    Summary observe(UUID therapySessionId, Origin origin, String originalAccessToken);

    enum Origin { SYNTHETIC, PRODUCER_REPORTED }

    /** Reported exercise outcomes, not physiological fixation or clinical efficacy. */
    record Summary(String calculationVersion, UUID therapySessionId, UUID configurationId,
            long configurationVersion, Origin dataOrigin, long reportedTrialCount,
            long totalInsideTargetMs, long completedTargetCount) {
        public Summary {
            Objects.requireNonNull(therapySessionId);
            Objects.requireNonNull(configurationId);
            Objects.requireNonNull(dataOrigin);
            if (!"sustained-fixation-trial-v1".equals(calculationVersion) || configurationVersion < 1
                    || reportedTrialCount < 0 || reportedTrialCount > 1000
                    || completedTargetCount < 0 || completedTargetCount > reportedTrialCount
                    || totalInsideTargetMs < completedTargetCount * 300
                    || totalInsideTargetMs > reportedTrialCount * 5000)
                throw new IllegalArgumentException("Invalid report sustained fixation values");
        }
        @Override public String toString() { return "ReportSustainedFixationSummary[redacted]"; }
    }
}

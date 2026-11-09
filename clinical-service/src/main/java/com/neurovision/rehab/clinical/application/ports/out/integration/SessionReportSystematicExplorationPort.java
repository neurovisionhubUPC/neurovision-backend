package com.neurovision.rehab.clinical.application.ports.out.integration;

import java.util.*;

/** Observed traversal results only; no gaze reconstruction or clinical interpretation. */
@FunctionalInterface
public interface SessionReportSystematicExplorationPort {
    Summary observe(UUID therapySessionId, Origin origin, String originalAccessToken);
    enum Origin { SYNTHETIC, PRODUCER_REPORTED }
    enum Outcome { COMPLETED, TIMED_OUT, INTERRUPTED }

    record Traversal(String trialId, Outcome outcome, int visitedRegionCount, int coveragePercent, long elapsedMs) {
        public Traversal {
            if (trialId == null || trialId.isBlank() || outcome == null || visitedRegionCount < 0
                    || visitedRegionCount > 4 || coveragePercent != visitedRegionCount * 25 || elapsedMs < 0
                    || (outcome == Outcome.COMPLETED) != (visitedRegionCount == 4))
                throw new IllegalArgumentException("Invalid report traversal values");
        }
        /** Checks consistency with the frozen policy, without recalculating producer metrics. */
        public void validateWindow(long minimumDwellMs, long maximumQuadrantMs) {
            long lower = visitedRegionCount * minimumDwellMs;
            long upper = visitedRegionCount * maximumQuadrantMs;
            if (outcome == Outcome.TIMED_OUT) { lower += maximumQuadrantMs; upper += maximumQuadrantMs; }
            if (outcome == Outcome.INTERRUPTED) upper += maximumQuadrantMs - 1;
            if (elapsedMs < lower || elapsedMs > upper) throw new IllegalArgumentException("Invalid report traversal timing");
        }
        @Override public String toString() { return "ReportTraversal[redacted]"; }
    }

    record Summary(String calculationVersion, UUID therapySessionId, UUID configurationId,
            long configurationVersion, Origin dataOrigin, long reportedTrialCount, List<Traversal> traversals) {
        public Summary {
            Objects.requireNonNull(therapySessionId); Objects.requireNonNull(configurationId); Objects.requireNonNull(dataOrigin);
            if (!"systematic-exploration-trial-v1".equals(calculationVersion) || configurationVersion < 1
                    || reportedTrialCount < 0 || reportedTrialCount > 1000 || traversals == null
                    || reportedTrialCount != traversals.size()) throw new IllegalArgumentException("Invalid report exploration values");
            traversals = List.copyOf(traversals);
            String previous = null;
            for (var row : traversals) {
                if (previous != null && previous.compareTo(row.trialId()) >= 0)
                    throw new IllegalArgumentException("Invalid report traversal order");
                long lower = row.visitedRegionCount() * 300L + (row.outcome() == Outcome.TIMED_OUT ? 2000 : 0);
                long upper = row.visitedRegionCount() * 10000L + (row.outcome() == Outcome.COMPLETED ? 0 : 10000);
                if (row.elapsedMs() < lower || row.elapsedMs() > upper)
                    throw new IllegalArgumentException("Invalid report traversal timing");
                previous = row.trialId();
            }
        }
        @Override public String toString() { return "ReportExplorationSummary[redacted]"; }
    }
}

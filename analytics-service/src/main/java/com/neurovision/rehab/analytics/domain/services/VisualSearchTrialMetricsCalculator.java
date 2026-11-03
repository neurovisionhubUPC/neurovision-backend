package com.neurovision.rehab.analytics.domain.services;

import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import java.math.BigDecimal;
import java.util.*;

/** Pure reduction of supplied results; no retained state, durable inbox, authorization or transport. */
public final class VisualSearchTrialMetricsCalculator {
    public static final String CALCULATION_VERSION = "visual-search-trial-v1";

    public Summary calculate(VisualSearchTrialContext context, DataOrigin origin, List<VisualSearchTrialResult> results) {
        if (context == null || origin == null || results == null)
            throw new IllegalArgumentException("Invalid Visual Search trial calculation");
        var unique = new HashMap<String, VisualSearchTrialResult>();
        for (var result : results) {
            context.validate(result);
            if (result.dataOrigin() != origin)
                throw new IllegalArgumentException("Invalid Visual Search trial data origin");
            var previous = unique.putIfAbsent(result.trialId(), result);
            if (previous != null && !previous.equals(result))
                throw new IllegalArgumentException("Conflicting Visual Search trial result");
        }

        var successfulDurations = new ArrayList<Long>();
        long omissions = 0, interrupted = 0;
        for (var result : unique.values()) {
            switch (result.outcome()) {
                case SUCCESS -> successfulDurations.add(result.searchDurationMs());
                case TIMEOUT -> omissions++;
                case INTERRUPTED -> interrupted++;
            }
        }
        successfulDurations.sort(Long::compare);
        Optional<BigDecimal> median = Optional.empty();
        int size = successfulDurations.size();
        if (size > 0) {
            var middle = BigDecimal.valueOf(successfulDurations.get(size / 2));
            median = Optional.of(size % 2 == 1 ? middle
                    : middle.add(BigDecimal.valueOf(successfulDurations.get(size / 2 - 1))).divide(BigDecimal.valueOf(2)));
        }
        return new Summary(CALCULATION_VERSION, context.therapySessionId(), context.configurationId(),
                context.configurationVersion(), origin, unique.size(), size, omissions, interrupted, size, median);
    }

    /** Counts describe only supplied distinct final results, never all presented or prescribed targets. */
    public record Summary(String calculationVersion, UUID therapySessionId, UUID configurationId,
            long configurationVersion, DataOrigin dataOrigin, long reportedTrialCount, long hitCount,
            long omissionCount, long interruptedCount, long eligibleSearchCount, Optional<BigDecimal> medianSearchDurationMs) {
        @Override public String toString() { return "VisualSearchTrialMetrics[redacted]"; }
    }
}

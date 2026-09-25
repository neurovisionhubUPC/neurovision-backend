package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.Objects;

/** Immutable view of the already-persisted baseline summary projection. */
public record AnalyticsSummaryProjection(
        AnalyticsProjectionIdentity identity,
        long revision,
        BaselineStreamSummary summary) {

    public AnalyticsSummaryProjection {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(summary, "summary");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (!identity.therapySessionId().equals(summary.therapySessionId())
                || !identity.streamId().equals(summary.streamId())
                || !identity.calculationVersion().equals(summary.calculationVersion())) {
            throw new IllegalArgumentException("Summary does not match projection identity");
        }
    }
}

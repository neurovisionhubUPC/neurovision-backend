package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.Objects;

public record StreamSummaryProjection(
        AnalyticsProjectionIdentity identity,
        long admittedBatchCount,
        BaselineStreamSummary summary) {

    public StreamSummaryProjection {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(summary, "summary");
        if (admittedBatchCount <= 0) {
            throw new IllegalArgumentException("admittedBatchCount must be positive");
        }
        if (!identity.equals(AnalyticsProjectionIdentity.from(summary))) {
            throw new IllegalArgumentException("Projection identity does not match summary");
        }
    }
}

package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

public record AnalyticsProjectionIdentity(
        UUID therapySessionId,
        UUID streamId,
        String calculationVersion) {

    public AnalyticsProjectionIdentity {
        Objects.requireNonNull(therapySessionId, "therapySessionId");
        Objects.requireNonNull(streamId, "streamId");
        if (calculationVersion == null || calculationVersion.isBlank()) {
            throw new IllegalArgumentException("calculationVersion is required");
        }
    }

    public static AnalyticsProjectionIdentity from(BaselineStreamSummary summary) {
        Objects.requireNonNull(summary, "summary");
        return new AnalyticsProjectionIdentity(
                summary.therapySessionId(), summary.streamId(), summary.calculationVersion());
    }
}

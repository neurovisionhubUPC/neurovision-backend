package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

public record TelemetryStreamIdentity(UUID therapySessionId, UUID streamId) {
    public TelemetryStreamIdentity {
        Objects.requireNonNull(therapySessionId, "therapySessionId");
        Objects.requireNonNull(streamId, "streamId");
    }
}

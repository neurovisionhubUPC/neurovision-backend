package com.neurovision.rehab.clinical.application.ports.out.authorization;

import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import java.time.Instant;
import java.util.Objects;

public record TelemetryAuthorizationFacts(TherapySessionStatus status, Instant leaseExpiresAt) {
    public TelemetryAuthorizationFacts {
        Objects.requireNonNull(status);
    }
}

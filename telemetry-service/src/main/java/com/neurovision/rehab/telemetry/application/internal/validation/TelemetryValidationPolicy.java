package com.neurovision.rehab.telemetry.application.internal.validation;

import java.time.Duration;
import java.util.Objects;

public record TelemetryValidationPolicy(int maximumSamples, Duration allowedFutureClockSkew) {

    public TelemetryValidationPolicy {
        if (maximumSamples <= 0) {
            throw new IllegalArgumentException("Maximum samples must be positive");
        }
        Objects.requireNonNull(allowedFutureClockSkew, "Allowed future clock skew must not be null");
        if (allowedFutureClockSkew.isNegative()) {
            throw new IllegalArgumentException("Allowed future clock skew must not be negative");
        }
    }
}

package com.neurovision.rehab.telemetry.application.internal.validation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;

public record TelemetryValidationResult(
        TelemetryBatch batch,
        Instant serverReceivedAt,
        TelemetryRejectionCategory rejectionCategory) {

    public TelemetryValidationResult {
        boolean accepted = batch != null && serverReceivedAt != null && rejectionCategory == null;
        boolean rejected = batch == null && serverReceivedAt == null && rejectionCategory != null;
        if (!accepted && !rejected) {
            throw new IllegalArgumentException("Validation result must be either accepted or rejected");
        }
    }

    public static TelemetryValidationResult accepted(TelemetryBatch batch, Instant serverReceivedAt) {
        return new TelemetryValidationResult(
                Objects.requireNonNull(batch, "Batch must not be null"),
                Objects.requireNonNull(serverReceivedAt, "Server received time must not be null"),
                null);
    }

    public static TelemetryValidationResult rejected(TelemetryRejectionCategory category) {
        return new TelemetryValidationResult(null, null, Objects.requireNonNull(category, "Category must not be null"));
    }

    public boolean isAccepted() {
        return rejectionCategory == null;
    }

    public Optional<TelemetryRejectionCategory> rejection() {
        return Optional.ofNullable(rejectionCategory);
    }
}

package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.Objects;

public record AdmittedTelemetryBatch(
        AcceptedTelemetryBatch batch,
        TelemetryAdmissionQuality admissionQuality) {

    public AdmittedTelemetryBatch {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(admissionQuality, "admissionQuality");
    }
}

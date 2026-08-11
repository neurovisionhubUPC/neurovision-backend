package com.neurovision.rehab.clinical.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

public record ClinicalPatientId(UUID value) {

    public ClinicalPatientId {
        Objects.requireNonNull(value, "Clinical patient ID must not be null");
    }
}

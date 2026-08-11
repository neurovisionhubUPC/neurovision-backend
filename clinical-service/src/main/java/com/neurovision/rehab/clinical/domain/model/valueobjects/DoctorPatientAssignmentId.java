package com.neurovision.rehab.clinical.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

public record DoctorPatientAssignmentId(UUID value) {

    public DoctorPatientAssignmentId {
        Objects.requireNonNull(value, "Doctor-patient assignment ID must not be null");
    }
}

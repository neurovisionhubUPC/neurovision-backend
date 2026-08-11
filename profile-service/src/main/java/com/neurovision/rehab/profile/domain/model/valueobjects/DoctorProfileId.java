package com.neurovision.rehab.profile.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

public record DoctorProfileId(UUID value) {

    public DoctorProfileId {
        Objects.requireNonNull(value, "Doctor profile ID must not be null");
    }
}

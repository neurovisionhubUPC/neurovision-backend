package com.neurovision.rehab.clinical.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

public record TherapeuticConfigurationId(UUID value) {
    public TherapeuticConfigurationId {
        Objects.requireNonNull(value, "Therapeutic configuration ID must not be null");
    }
}

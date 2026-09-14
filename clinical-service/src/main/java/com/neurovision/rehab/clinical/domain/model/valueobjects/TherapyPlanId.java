package com.neurovision.rehab.clinical.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

public record TherapyPlanId(UUID value) {
    public TherapyPlanId {
        Objects.requireNonNull(value, "Therapy plan ID must not be null");
    }
}

package com.neurovision.rehab.clinical.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

public record TherapyPlanRevisionId(UUID value) {
    public TherapyPlanRevisionId {
        Objects.requireNonNull(value, "Therapy plan revision ID must not be null");
    }
}

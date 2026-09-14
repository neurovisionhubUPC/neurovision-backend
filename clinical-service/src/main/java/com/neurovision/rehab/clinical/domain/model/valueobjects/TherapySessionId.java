package com.neurovision.rehab.clinical.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

public record TherapySessionId(UUID value) {
    public TherapySessionId {
        Objects.requireNonNull(value, "Therapy session ID must not be null");
    }
}

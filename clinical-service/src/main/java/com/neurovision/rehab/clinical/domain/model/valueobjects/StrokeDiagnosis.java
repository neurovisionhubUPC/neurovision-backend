package com.neurovision.rehab.clinical.domain.model.valueobjects;

public record StrokeDiagnosis(String value) {

    public StrokeDiagnosis {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Stroke diagnosis must not be blank");
        }
        value = value.trim();
    }
}

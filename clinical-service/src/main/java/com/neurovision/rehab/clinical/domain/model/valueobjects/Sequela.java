package com.neurovision.rehab.clinical.domain.model.valueobjects;

public record Sequela(String value) {

    public Sequela {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Sequela must not be blank");
        }
        value = value.trim();
    }
}

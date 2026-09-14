package com.neurovision.rehab.clinical.domain.model.valueobjects;

import java.util.Objects;

public record IdempotencyKey(String value) {
    private static final int MAX_LENGTH = 128;

    public IdempotencyKey {
        Objects.requireNonNull(value, "Idempotency key must not be null");
        value = value.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Idempotency key must contain between 1 and 128 characters");
        }
    }
}

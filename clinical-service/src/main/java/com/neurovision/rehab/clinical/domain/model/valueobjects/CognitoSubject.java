package com.neurovision.rehab.clinical.domain.model.valueobjects;

public record CognitoSubject(String value) {

    public static final int MAX_LENGTH = 128;

    public CognitoSubject {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Cognito subject must not be blank");
        }
        value = value.trim();
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Cognito subject must not exceed 128 characters");
        }
    }
}

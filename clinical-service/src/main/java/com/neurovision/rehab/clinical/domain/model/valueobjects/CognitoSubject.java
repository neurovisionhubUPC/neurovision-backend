package com.neurovision.rehab.clinical.domain.model.valueobjects;

public record CognitoSubject(String value) {

    public CognitoSubject {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Cognito subject must not be blank");
        }
        value = value.trim();
    }
}

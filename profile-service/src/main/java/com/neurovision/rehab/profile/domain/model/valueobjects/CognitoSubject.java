package com.neurovision.rehab.profile.domain.model.valueobjects;

public record CognitoSubject(String value) {

    private static final int MAX_LENGTH = 128;

    public CognitoSubject {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Cognito subject must not be blank");
        }

        value = value.trim();
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Cognito subject exceeds " + MAX_LENGTH + " characters");
        }
    }
}

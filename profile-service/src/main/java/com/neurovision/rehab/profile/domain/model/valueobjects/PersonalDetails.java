package com.neurovision.rehab.profile.domain.model.valueobjects;

import java.time.LocalDate;
import java.time.ZoneOffset;

public record PersonalDetails(
        String firstName,
        String lastName,
        String documentNumber,
        LocalDate birthDate,
        String phone) {

    public PersonalDetails {
        firstName = requireText(firstName, "First name", 1, 100);
        lastName = requireText(lastName, "Last name", 1, 100);
        documentNumber = documentNumber == null ? null : requireText(documentNumber, "Document number", 3, 32);
        if (birthDate != null && birthDate.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw new IllegalArgumentException("Birth date must not be in the future");
        }
        phone = phone == null ? null : requireText(phone, "Phone", 7, 20);
    }

    private static String requireText(String value, String field, int min, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        value = value.trim().replaceAll("\\s+", " ");
        if (value.length() < min || value.length() > max) {
            throw new IllegalArgumentException(
                    field + " length must be between " + min + " and " + max);
        }
        return value;
    }
}

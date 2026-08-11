package com.neurovision.rehab.profile.domain.model.valueobjects;

import java.util.Locale;

public record ProfessionalDetails(String specialty, String medicalLicense) {

    public ProfessionalDetails {
        specialty = requireText(specialty, "Specialty", 2, 100);
        medicalLicense = requireText(medicalLicense, "Medical license", 3, 64)
                .toUpperCase(Locale.ROOT);
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

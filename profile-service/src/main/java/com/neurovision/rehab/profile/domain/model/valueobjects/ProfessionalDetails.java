package com.neurovision.rehab.profile.domain.model.valueobjects;

import java.util.Objects;

/** Optional professional fields; new submissions use Peru CMP syntax, not registry verification. */
public final class ProfessionalDetails {
    private final String specialty;
    private final String medicalLicense;

    public ProfessionalDetails(String specialty, String medicalLicense) {
        this(specialty, medicalLicense, false);
    }

    private ProfessionalDetails(String specialty, String medicalLicense, boolean stored) {
        this.specialty = specialty == null ? null : requireText(specialty, "Specialty", 2, 100);
        if (stored) {
            // Existing values remain readable, including pre-CMP-format licenses. Never convert
            // them into a valid CMP implicitly; Clinical checks eligibility independently.
            this.medicalLicense = medicalLicense == null ? null
                    : requireText(medicalLicense, "Medical license", 3, 64).toUpperCase(java.util.Locale.ROOT);
        } else {
            this.medicalLicense = medicalLicense == null ? null : medicalLicense.trim();
            if (this.medicalLicense != null && !validCmp(this.medicalLicense)) {
                throw new IllegalArgumentException("Medical license must contain six ASCII digits and must not be 000000");
            }
        }
    }

    /** Persistence-only rehydration; not an admission path for new profile submissions. */
    public static ProfessionalDetails fromStored(String specialty, String medicalLicense) {
        return new ProfessionalDetails(specialty, medicalLicense, true);
    }

    private static boolean validCmp(String value) {
        return value.matches("[0-9]{6}") && !value.equals("000000");
    }

    private static String requireText(String value, String field, int min, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        value = value.trim().replaceAll("\\s+", " ");
        if (value.length() < min || value.length() > max) {
            throw new IllegalArgumentException(field + " length must be between " + min + " and " + max);
        }
        return value;
    }

    public String specialty() { return specialty; }
    public String medicalLicense() { return medicalLicense; }
    @Override public boolean equals(Object other) {
        return other instanceof ProfessionalDetails details
                && Objects.equals(specialty, details.specialty)
                && Objects.equals(medicalLicense, details.medicalLicense);
    }
    @Override public int hashCode() { return Objects.hash(specialty, medicalLicense); }
    @Override public String toString() { return "ProfessionalDetails[redacted]"; }
}

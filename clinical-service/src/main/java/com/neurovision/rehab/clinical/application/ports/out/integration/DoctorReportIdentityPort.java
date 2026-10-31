package com.neurovision.rehab.clinical.application.ports.out.integration;

import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import java.util.Objects;

/** Current Profile identity at issuance, not scheduling eligibility. Failures must throw. */
public interface DoctorReportIdentityPort {
    Identity lookup(String authenticatedDoctorSubject, String originalAccessToken);

    enum Availability { AVAILABLE, MISSING, PENDING_COMPLETION }
    record Identity(String doctorSubject, Availability availability, String firstName, String lastName,
            String specialty, String medicalLicense) {
        public Identity {
            if (!new CognitoSubject(doctorSubject).value().equals(doctorSubject))
                throw new IllegalArgumentException("Invalid doctor identity");
            Objects.requireNonNull(availability);
            if (availability == Availability.AVAILABLE) {
                if (!text(firstName) || !text(lastName) || !optional(specialty) || !optional(medicalLicense))
                    throw new IllegalArgumentException("Invalid doctor identity");
            } else if (firstName != null || lastName != null || specialty != null || medicalLicense != null) {
                throw new IllegalArgumentException("Unavailable identity must not contain fields");
            }
        }
        private static boolean text(String value) { return value != null && !value.isBlank() && value.length() <= 200; }
        private static boolean optional(String value) { return value == null || text(value); }
        @Override public String toString() { return "DoctorIdentity[redacted]"; }
    }
}

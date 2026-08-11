package com.neurovision.rehab.clinical.application.internal.commandservices;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RegisterInvitedClinicalPatientCommand(
        UUID eventId, String doctorSubject, String patientSubject, Instant occurredAt) {

    public RegisterInvitedClinicalPatientCommand {
        Objects.requireNonNull(eventId, "Event ID must not be null");
        doctorSubject = requireText(doctorSubject, "Doctor subject must not be blank");
        patientSubject = requireText(patientSubject, "Patient subject must not be blank");
        Objects.requireNonNull(occurredAt, "Event occurrence time must not be null");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}

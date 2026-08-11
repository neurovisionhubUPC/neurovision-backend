package com.neurovision.rehab.profile.application.internal.commandservices;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RegisterInvitedPatientCommand(
        UUID eventId, String patientSubject, Instant occurredAt) {

    public RegisterInvitedPatientCommand {
        Objects.requireNonNull(eventId, "Event ID must not be null");
        if (patientSubject == null || patientSubject.isBlank()) {
            throw new IllegalArgumentException("Patient subject must not be blank");
        }
        patientSubject = patientSubject.trim();
        Objects.requireNonNull(occurredAt, "Occurrence time must not be null");
    }
}

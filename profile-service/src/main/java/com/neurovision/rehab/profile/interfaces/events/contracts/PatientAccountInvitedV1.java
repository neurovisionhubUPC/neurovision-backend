package com.neurovision.rehab.profile.interfaces.events.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PatientAccountInvitedV1(
        UUID eventId,
        UUID invitationId,
        String invitedByDoctor,
        String patientSubject,
        Instant occurredAt) {

    public PatientAccountInvitedV1 {
        Objects.requireNonNull(eventId, "Event ID must not be null");
        Objects.requireNonNull(invitationId, "Invitation ID must not be null");
        invitedByDoctor = requireText(invitedByDoctor, "Inviting doctor must not be blank");
        patientSubject = requireText(patientSubject, "Patient subject must not be blank");
        Objects.requireNonNull(occurredAt, "Occurrence time must not be null");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}

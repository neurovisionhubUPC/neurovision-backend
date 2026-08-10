package com.neurovision.rehab.iam.application.ports.out.messaging;

import com.neurovision.rehab.iam.domain.model.events.PatientInvitationCompleted;
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
        requireText(invitedByDoctor, "Inviting doctor must not be blank");
        requireText(patientSubject, "Patient subject must not be blank");
        Objects.requireNonNull(occurredAt, "Occurrence time must not be null");
    }

    public static PatientAccountInvitedV1 from(PatientInvitationCompleted event) {
        Objects.requireNonNull(event, "Domain event must not be null");
        return new PatientAccountInvitedV1(
                event.eventId(),
                event.invitationId().value(),
                event.invitedByDoctor().value(),
                event.patientSubject().value(),
                event.occurredAt());
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}

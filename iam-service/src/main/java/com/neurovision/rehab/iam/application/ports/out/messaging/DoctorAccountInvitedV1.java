package com.neurovision.rehab.iam.application.ports.out.messaging;

import com.neurovision.rehab.iam.domain.model.events.DoctorInvitationCompleted;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DoctorAccountInvitedV1(
        UUID eventId,
        UUID invitationId,
        String invitedByAdministrator,
        String doctorSubject,
        Instant occurredAt) {

    public DoctorAccountInvitedV1 {
        Objects.requireNonNull(eventId, "Event ID must not be null");
        Objects.requireNonNull(invitationId, "Invitation ID must not be null");
        requireText(invitedByAdministrator, "Inviting administrator must not be blank");
        requireText(doctorSubject, "Doctor subject must not be blank");
        Objects.requireNonNull(occurredAt, "Occurrence time must not be null");
    }

    public static DoctorAccountInvitedV1 from(DoctorInvitationCompleted event) {
        Objects.requireNonNull(event, "Domain event must not be null");
        return new DoctorAccountInvitedV1(
                event.eventId(),
                event.invitationId().value(),
                event.invitedByAdministrator().value(),
                event.doctorSubject().value(),
                event.occurredAt());
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}

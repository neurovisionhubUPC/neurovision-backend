package com.neurovision.rehab.profile.interfaces.events.contracts;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@RegisterForReflection
public record DoctorAccountInvitedV1(
        UUID eventId,
        UUID invitationId,
        String invitedByAdministrator,
        String doctorSubject,
        Instant occurredAt) {

    public DoctorAccountInvitedV1 {
        Objects.requireNonNull(eventId, "Event ID must not be null");
        Objects.requireNonNull(invitationId, "Invitation ID must not be null");
        invitedByAdministrator = requireText(
                invitedByAdministrator, "Inviting administrator must not be blank");
        doctorSubject = requireText(doctorSubject, "Doctor subject must not be blank");
        Objects.requireNonNull(occurredAt, "Occurrence time must not be null");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}

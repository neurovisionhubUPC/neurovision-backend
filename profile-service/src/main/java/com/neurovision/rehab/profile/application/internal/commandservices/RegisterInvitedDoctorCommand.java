package com.neurovision.rehab.profile.application.internal.commandservices;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RegisterInvitedDoctorCommand(
        UUID eventId, String doctorSubject, Instant occurredAt) {

    public RegisterInvitedDoctorCommand {
        Objects.requireNonNull(eventId, "Event ID must not be null");
        if (doctorSubject == null || doctorSubject.isBlank()) {
            throw new IllegalArgumentException("Doctor subject must not be blank");
        }
        doctorSubject = doctorSubject.trim();
        Objects.requireNonNull(occurredAt, "Occurrence time must not be null");
    }
}

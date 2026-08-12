package com.neurovision.rehab.iam.domain.model.events;

import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DoctorInvitationCompleted(
        UUID eventId,
        InvitationId invitationId,
        CognitoSubject invitedByAdministrator,
        CognitoSubject doctorSubject,
        Instant occurredAt) {

    public DoctorInvitationCompleted {
        Objects.requireNonNull(eventId, "Event ID must not be null");
        Objects.requireNonNull(invitationId, "Invitation ID must not be null");
        Objects.requireNonNull(
                invitedByAdministrator, "Inviting administrator must not be null");
        Objects.requireNonNull(doctorSubject, "Doctor subject must not be null");
        Objects.requireNonNull(occurredAt, "Occurrence time must not be null");
    }
}

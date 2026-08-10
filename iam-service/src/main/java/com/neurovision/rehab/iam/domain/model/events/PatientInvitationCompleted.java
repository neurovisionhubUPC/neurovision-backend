package com.neurovision.rehab.iam.domain.model.events;

import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PatientInvitationCompleted(
        UUID eventId,
        InvitationId invitationId,
        CognitoSubject invitedByDoctor,
        CognitoSubject patientSubject,
        Instant occurredAt) {

    public PatientInvitationCompleted {
        Objects.requireNonNull(eventId, "Event ID must not be null");
        Objects.requireNonNull(invitationId, "Invitation ID must not be null");
        Objects.requireNonNull(invitedByDoctor, "Inviting doctor must not be null");
        Objects.requireNonNull(patientSubject, "Patient subject must not be null");
        Objects.requireNonNull(occurredAt, "Occurrence time must not be null");
    }
}

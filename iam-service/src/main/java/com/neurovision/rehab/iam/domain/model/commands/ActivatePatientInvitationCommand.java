package com.neurovision.rehab.iam.domain.model.commands;

import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import java.time.Instant;
import java.util.Objects;

public record ActivatePatientInvitationCommand(
        CognitoSubject patientSubject, Instant authenticatedAt) {

    public ActivatePatientInvitationCommand {
        Objects.requireNonNull(patientSubject, "Patient subject must not be null");
        Objects.requireNonNull(authenticatedAt, "Authentication time must not be null");
    }
}

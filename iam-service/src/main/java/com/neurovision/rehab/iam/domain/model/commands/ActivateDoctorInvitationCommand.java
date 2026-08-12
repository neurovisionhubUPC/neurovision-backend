package com.neurovision.rehab.iam.domain.model.commands;

import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import java.time.Instant;
import java.util.Objects;

public record ActivateDoctorInvitationCommand(
        CognitoSubject doctorSubject, Instant authenticatedAt) {

    public ActivateDoctorInvitationCommand {
        Objects.requireNonNull(doctorSubject, "Doctor subject must not be null");
        Objects.requireNonNull(authenticatedAt, "Authentication time must not be null");
    }
}

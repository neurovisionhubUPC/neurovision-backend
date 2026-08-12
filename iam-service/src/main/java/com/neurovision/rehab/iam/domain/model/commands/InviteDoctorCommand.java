package com.neurovision.rehab.iam.domain.model.commands;

import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import java.util.Objects;

public record InviteDoctorCommand(
        EmailAddress doctorEmail, CognitoSubject invitedByAdministrator) {

    public InviteDoctorCommand {
        Objects.requireNonNull(doctorEmail, "Doctor email must not be null");
        Objects.requireNonNull(
                invitedByAdministrator, "Inviting administrator must not be null");
    }
}

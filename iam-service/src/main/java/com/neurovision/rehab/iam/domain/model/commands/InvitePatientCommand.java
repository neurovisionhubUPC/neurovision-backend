package com.neurovision.rehab.iam.domain.model.commands;

import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import java.util.Objects;

public record InvitePatientCommand(EmailAddress patientEmail, CognitoSubject invitedByDoctor) {

    public InvitePatientCommand {
        Objects.requireNonNull(patientEmail, "Patient email must not be null");
        Objects.requireNonNull(invitedByDoctor, "Inviting doctor must not be null");
    }
}

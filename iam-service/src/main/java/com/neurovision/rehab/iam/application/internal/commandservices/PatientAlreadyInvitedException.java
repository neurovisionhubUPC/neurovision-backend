package com.neurovision.rehab.iam.application.internal.commandservices;

public final class PatientAlreadyInvitedException extends RuntimeException {

    public PatientAlreadyInvitedException() {
        super("An active invitation already exists for this patient email");
    }
}

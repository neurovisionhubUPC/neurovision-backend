package com.neurovision.rehab.iam.application.internal.commandservices;

public class PatientInvitationNotFoundException extends RuntimeException {

    public PatientInvitationNotFoundException() {
        super("No patient invitation exists for the authenticated account");
    }
}

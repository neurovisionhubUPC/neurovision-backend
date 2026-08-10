package com.neurovision.rehab.iam.application.internal.commandservices;

public final class PatientInvitationProvisioningException extends RuntimeException {

    public PatientInvitationProvisioningException(Throwable cause) {
        super("Unable to provision the patient identity", cause);
    }
}

package com.neurovision.rehab.iam.application.internal.commandservices;

public final class DoctorInvitationProvisioningException extends RuntimeException {

    public DoctorInvitationProvisioningException(Throwable cause) {
        super("Unable to provision the doctor identity", cause);
    }
}

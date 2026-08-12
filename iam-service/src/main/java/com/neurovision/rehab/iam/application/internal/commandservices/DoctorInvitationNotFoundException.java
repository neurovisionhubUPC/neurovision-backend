package com.neurovision.rehab.iam.application.internal.commandservices;

public final class DoctorInvitationNotFoundException extends RuntimeException {

    public DoctorInvitationNotFoundException() {
        super("Doctor invitation was not found");
    }
}

package com.neurovision.rehab.iam.application.internal.commandservices;

public final class DoctorAlreadyInvitedException extends RuntimeException {

    public DoctorAlreadyInvitedException() {
        super("An active invitation already exists for this doctor email");
    }
}

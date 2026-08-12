package com.neurovision.rehab.profile.interfaces.events.transform;

public class InvalidDoctorAccountInvitedEventException extends RuntimeException {

    public InvalidDoctorAccountInvitedEventException(Throwable cause) {
        super("Invalid DoctorAccountInvitedV1 payload", cause);
    }
}

package com.neurovision.rehab.profile.interfaces.events.transform;

public final class InvalidPatientAccountInvitedEventException extends RuntimeException {

    public InvalidPatientAccountInvitedEventException(Throwable cause) {
        super("Invalid PatientAccountInvitedV1 payload", cause);
    }
}

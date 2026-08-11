package com.neurovision.rehab.clinical.interfaces.events.transform;

public final class InvalidPatientAccountInvitedEventException extends RuntimeException {

    public InvalidPatientAccountInvitedEventException(Throwable cause) {
        super("Invalid PatientAccountInvitedV1 payload", cause);
    }
}

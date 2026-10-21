package com.neurovision.rehab.profile.application.internal.queryservices;

public final class PatientNameFailure extends RuntimeException {
    public enum Kind { INVALID_REQUEST, UNAUTHENTICATED, FORBIDDEN, UNAVAILABLE }
    private final Kind kind;
    public PatientNameFailure(Kind kind) { super("PATIENT_READ_" + kind.name(), null, false, false); this.kind = kind; }
    public Kind kind() { return kind; }
}

package com.neurovision.rehab.clinical.application.internal.queryservices;

public final class PatientReadFailure extends RuntimeException {
    public enum Kind { INVALID_REQUEST, UNAUTHENTICATED, FORBIDDEN, UNAVAILABLE }
    private final Kind kind;
    public PatientReadFailure(Kind kind) { super("PATIENT_READ_" + kind.name(), null, false, false); this.kind = kind; }
    public Kind kind() { return kind; }
}

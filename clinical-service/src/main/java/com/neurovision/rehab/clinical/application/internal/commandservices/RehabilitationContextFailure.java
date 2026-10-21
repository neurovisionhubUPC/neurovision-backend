package com.neurovision.rehab.clinical.application.internal.commandservices;

public final class RehabilitationContextFailure extends RuntimeException {
    public enum Kind { INVALID_REQUEST, FORBIDDEN, CONFLICT, UNAVAILABLE }
    private final Kind kind;
    public RehabilitationContextFailure(Kind kind) {
        super("Rehabilitation context request could not be completed");
        this.kind = java.util.Objects.requireNonNull(kind);
    }
    public Kind kind() { return kind; }
}

package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import java.util.Optional;

public final class TherapySessionStartConflictException extends RuntimeException {
    private final TherapySessionId activeSessionId;

    private TherapySessionStartConflictException(String message, TherapySessionId activeSessionId) {
        super(message);
        this.activeSessionId = activeSessionId;
    }

    public static TherapySessionStartConflictException idempotencyMismatch() {
        return new TherapySessionStartConflictException("Idempotency key was reused with a different request", null);
    }

    public static TherapySessionStartConflictException activeSession(TherapySessionId id) {
        return new TherapySessionStartConflictException("Patient already has an active therapy session", id);
    }

    public static TherapySessionStartConflictException admissionDenied() {
        return new TherapySessionStartConflictException("Therapy session admission is not permitted", null);
    }

    public Optional<TherapySessionId> activeSessionId() { return Optional.ofNullable(activeSessionId); }
}

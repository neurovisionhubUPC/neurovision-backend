package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapySession;
import java.util.Objects;

public record StartTherapySessionResult(Outcome outcome, TherapySession session) {
    public StartTherapySessionResult {
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(session);
    }

    public enum Outcome { CREATED, IDEMPOTENT_REPLAY }
}

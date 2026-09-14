package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import java.time.Instant;
import java.util.Objects;

public record TransitionTherapySessionCommand(
        TherapySessionId therapySessionId, Action action, String reason, Instant occurredAt) {
    public TransitionTherapySessionCommand {
        Objects.requireNonNull(therapySessionId);
        Objects.requireNonNull(action);
        Objects.requireNonNull(occurredAt);
    }

    public enum Action { COMPLETE, CANCEL, ABANDON, FAIL_TECHNICAL, EXPIRE_LEASE }
}

package com.neurovision.rehab.clinical.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

public record SessionActivityEvent(int schemaVersion, UUID eventId, long sequence, Type type,
        UUID repetitionId, Integer repetitionIndex) {
    public enum Type { ACTIVITY_STARTED, HEARTBEAT, PAUSED, RESUMED, REPETITION_STARTED, REPETITION_FINISHED }
    public SessionActivityEvent {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(type);
        if (schemaVersion != 1 || sequence < 0 || sequence == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid activity event");
        }
        boolean repetition = type == Type.REPETITION_STARTED || type == Type.REPETITION_FINISHED;
        if (repetition ? repetitionId == null || repetitionIndex == null || repetitionIndex < 1
                : repetitionId != null || repetitionIndex != null) {
            throw new IllegalArgumentException("Invalid repetition fields");
        }
    }
}

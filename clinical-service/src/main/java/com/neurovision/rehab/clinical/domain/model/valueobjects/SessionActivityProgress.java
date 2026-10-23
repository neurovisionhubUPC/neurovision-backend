package com.neurovision.rehab.clinical.domain.model.valueobjects;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Immutable server-receipt-time accounting. Rejections cannot partially advance state. */
public record SessionActivityProgress(long nextSequence, long activeMilliseconds, long completedRepetitions,
        Mode mode, Instant anchor, UUID repetitionId, Integer repetitionIndex, Set<UUID> usedRepetitions) {
    public enum Mode { NEW, ACTIVE, PAUSED, RESUME_REQUIRED }
    public SessionActivityProgress {
        Objects.requireNonNull(mode);
        usedRepetitions = Set.copyOf(usedRepetitions);
        if (nextSequence < 0 || activeMilliseconds < 0 || completedRepetitions < 0
                || (repetitionId == null) != (repetitionIndex == null)) throw new IllegalArgumentException("Invalid progress");
    }
    public static SessionActivityProgress empty() {
        return new SessionActivityProgress(0, 0, 0, Mode.NEW, null, null, null, Set.of());
    }
    public SessionActivityProgress accept(SessionActivityEvent event, Instant now) {
        Objects.requireNonNull(event); Objects.requireNonNull(now);
        if (event.sequence() != nextSequence) throw new IllegalStateException("Activity sequence conflict");
        if (anchor != null && now.isBefore(anchor)) throw new IllegalStateException("Server time moved backwards");
        long elapsed = mode == Mode.ACTIVE ? Duration.between(anchor, now).toMillis() : 0;
        boolean gap = mode == Mode.ACTIVE && Duration.between(anchor, now).compareTo(Duration.ofSeconds(15)) > 0;
        Mode nextMode = mode;
        long active = activeMilliseconds;
        long repetitions = completedRepetitions;
        UUID open = repetitionId;
        Integer index = repetitionIndex;
        var used = new HashSet<>(usedRepetitions);
        switch (event.type()) {
            case ACTIVITY_STARTED -> {
                if (mode != Mode.NEW) throw new IllegalStateException("Activity already started");
                nextMode = Mode.ACTIVE;
            }
            case RESUMED -> {
                if (mode != Mode.PAUSED && mode != Mode.RESUME_REQUIRED && !gap) {
                    throw new IllegalStateException("Activity is not paused");
                }
                nextMode = Mode.ACTIVE; open = null; index = null;
            }
            case HEARTBEAT, PAUSED, REPETITION_STARTED, REPETITION_FINISHED -> {
                if (mode != Mode.ACTIVE) throw new IllegalStateException("Activity is not active");
                if (gap) {
                    nextMode = event.type() == SessionActivityEvent.Type.PAUSED ? Mode.PAUSED : Mode.RESUME_REQUIRED;
                    open = null; index = null;
                } else {
                    active = Math.addExact(active, elapsed);
                    switch (event.type()) {
                        case PAUSED -> { nextMode = Mode.PAUSED; open = null; index = null; }
                        case REPETITION_STARTED -> {
                            if (open != null || event.repetitionIndex() != repetitions + 1 || !used.add(event.repetitionId())) {
                                throw new IllegalStateException("Repetition conflict");
                            }
                            open = event.repetitionId(); index = event.repetitionIndex();
                        }
                        case REPETITION_FINISHED -> {
                            if (!Objects.equals(open, event.repetitionId()) || !Objects.equals(index, event.repetitionIndex())) {
                                throw new IllegalStateException("Repetition conflict");
                            }
                            repetitions = Math.addExact(repetitions, 1); open = null; index = null;
                        }
                        default -> { }
                    }
                }
            }
        }
        return new SessionActivityProgress(nextSequence + 1, active, repetitions, nextMode,
                now, open, index, used);
    }
}

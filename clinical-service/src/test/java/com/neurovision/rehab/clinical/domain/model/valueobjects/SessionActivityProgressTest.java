package com.neurovision.rehab.clinical.domain.model.valueobjects;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionActivityProgressTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static SessionActivityEvent event(long sequence, SessionActivityEvent.Type type) {
        return new SessionActivityEvent(1, UUID.randomUUID(), sequence, type, null, null);
    }
    @Test void creditsInclusiveWindowButNotPauseOrGap() {
        var p = SessionActivityProgress.empty().accept(event(0, SessionActivityEvent.Type.ACTIVITY_STARTED), NOW);
        p = p.accept(event(1, SessionActivityEvent.Type.HEARTBEAT), NOW.plusSeconds(15));
        assertEquals(15000, p.activeMilliseconds());
        p = p.accept(event(2, SessionActivityEvent.Type.PAUSED), NOW.plusSeconds(20));
        assertEquals(20000, p.activeMilliseconds());
        p = p.accept(event(3, SessionActivityEvent.Type.RESUMED), NOW.plusSeconds(100));
        assertEquals(20000, p.activeMilliseconds());
        p = p.accept(event(4, SessionActivityEvent.Type.HEARTBEAT), NOW.plusSeconds(116));
        assertEquals(20000, p.activeMilliseconds());
        assertEquals(SessionActivityProgress.Mode.RESUME_REQUIRED, p.mode());
        final var gap = p;
        assertThrows(IllegalStateException.class, () -> gap.accept(event(5, SessionActivityEvent.Type.HEARTBEAT), NOW.plusSeconds(117)));
        assertEquals(20000, p.accept(event(5, SessionActivityEvent.Type.RESUMED), NOW.plusSeconds(118)).activeMilliseconds());
    }
    @Test void rejectsOrderingAndDoesNotMutatePreviousProgress() {
        var p = SessionActivityProgress.empty();
        assertThrows(IllegalStateException.class, () -> p.accept(event(1, SessionActivityEvent.Type.ACTIVITY_STARTED), NOW));
        assertEquals(0, p.nextSequence());
        assertThrows(IllegalStateException.class, () -> p.accept(event(0, SessionActivityEvent.Type.HEARTBEAT), NOW));
    }
    @Test void countsMatchedRepetitionOnceAndPauseInterruptsAttempt() {
        var p = SessionActivityProgress.empty().accept(event(0, SessionActivityEvent.Type.ACTIVITY_STARTED), NOW);
        var attempt = UUID.randomUUID();
        p = p.accept(new SessionActivityEvent(1, UUID.randomUUID(), 1, SessionActivityEvent.Type.REPETITION_STARTED, attempt, 1), NOW.plusSeconds(1));
        p = p.accept(event(2, SessionActivityEvent.Type.PAUSED), NOW.plusSeconds(2));
        p = p.accept(event(3, SessionActivityEvent.Type.RESUMED), NOW.plusSeconds(3));
        final var resumed = p;
        assertThrows(IllegalStateException.class, () -> resumed.accept(new SessionActivityEvent(1, UUID.randomUUID(), 4,
                SessionActivityEvent.Type.REPETITION_FINISHED, attempt, 1), NOW.plusSeconds(4)));
        assertThrows(IllegalStateException.class, () -> resumed.accept(new SessionActivityEvent(1, UUID.randomUUID(), 4,
                SessionActivityEvent.Type.REPETITION_STARTED, attempt, 1), NOW.plusSeconds(4)));
        var fresh = UUID.randomUUID();
        p = p.accept(new SessionActivityEvent(1, UUID.randomUUID(), 4, SessionActivityEvent.Type.REPETITION_STARTED, fresh, 1), NOW.plusSeconds(4));
        p = p.accept(new SessionActivityEvent(1, UUID.randomUUID(), 5, SessionActivityEvent.Type.REPETITION_FINISHED, fresh, 1), NOW.plusSeconds(5));
        assertEquals(1, p.completedRepetitions());
    }
    @Test void validatesExplicitTargetAndDoesNotInferLegacyTarget() {
        assertThrows(IllegalArgumentException.class, () -> new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 0));
        var target = new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 15);
        var p = SessionActivityProgress.empty().accept(event(0, SessionActivityEvent.Type.ACTIVITY_STARTED), NOW)
                .accept(event(1, SessionActivityEvent.Type.HEARTBEAT), NOW.plusSeconds(15));
        assertTrue(target.reached(p));
        assertFalse(new CompletionTarget(CompletionTarget.Criterion.REPETITIONS, 1).reached(p));
    }
}

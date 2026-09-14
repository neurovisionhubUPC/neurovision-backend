package com.neurovision.rehab.clinical.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TransitionTherapySessionCommandServiceTest {
    @Test
    void shouldTransitionAndPersistUnderRepositoryLock() {
        var session = activeSession();
        var repository = new FakeRepository(session);
        var service = new TransitionTherapySessionCommandService(repository);

        var result = service.handle(new TransitionTherapySessionCommand(
                session.id(), TransitionTherapySessionCommand.Action.CANCEL,
                "Safety interruption", Instant.parse("2026-09-13T15:01:00Z")));

        assertEquals(TherapySessionStatus.CANCELLED, result.status());
        assertTrue(repository.lockRead);
        assertEquals(1, repository.saveCalls);
    }

    @Test
    void shouldRejectCompetingTransitionAfterTerminalState() {
        var session = activeSession();
        var repository = new FakeRepository(session);
        var service = new TransitionTherapySessionCommandService(repository);
        service.handle(new TransitionTherapySessionCommand(session.id(),
                TransitionTherapySessionCommand.Action.COMPLETE, null,
                Instant.parse("2026-09-13T15:01:00Z")));

        assertThrows(IllegalStateException.class, () -> service.handle(new TransitionTherapySessionCommand(
                session.id(), TransitionTherapySessionCommand.Action.ABANDON, "Disconnected",
                Instant.parse("2026-09-13T15:02:00Z"))));
    }

    private static TherapySession activeSession() {
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(UUID.randomUUID()), new ClinicalPatientId(UUID.randomUUID()),
                new CognitoSubject("doctor-subject"), TherapyPlanStatus.ACTIVE, 1, 3, 30, null, 5, 15, 50,
                Map.of(new ExerciseCode("visual-search"), 1));
        var start = Instant.parse("2026-09-13T15:00:00Z");
        return TherapySession.start(new TherapySessionId(UUID.randomUUID()), plan, new ExerciseCode("visual-search"),
                LocalDate.of(2026, 9, 13), new IdempotencyKey("attempt-1"), "a".repeat(64), start, start.plusSeconds(120));
    }

    private static final class FakeRepository implements TherapySessionRepository {
        private final TherapySession session;
        private boolean lockRead;
        private int saveCalls;
        private FakeRepository(TherapySession session) { this.session = session; }
        @Override public void acquirePatientStartLock(ClinicalPatientId patientId) {}
        @Override public Optional<TherapySession> findByPatientAndIdempotencyKey(ClinicalPatientId patientId, IdempotencyKey key) { return Optional.empty(); }
        @Override public Optional<TherapySession> findActiveByPatient(ClinicalPatientId patientId) { return Optional.empty(); }
        @Override public Optional<TherapySession> findActiveByPlanForUpdate(TherapyPlanId planId) {
            return session.therapyPlanId().equals(planId) && session.status() == TherapySessionStatus.ACTIVE
                    ? Optional.of(session) : Optional.empty();
        }
        @Override public Optional<TherapySession> findByIdForUpdate(TherapySessionId sessionId) { lockRead = true; return session.id().equals(sessionId) ? Optional.of(session) : Optional.empty(); }
        @Override public long countConsumedSlots(TherapyPlanId planId, LocalDate quotaDate) { return 0; }
        @Override public long countConsumedExerciseSlots(TherapyPlanId planId, LocalDate quotaDate, ExerciseCode exerciseCode) { return 0; }
        @Override public long sumExposureSeconds(TherapyPlanId planId, LocalDate quotaDate) { return 0; }
        @Override public void save(TherapySession session) { saveCalls++; }
    }
}

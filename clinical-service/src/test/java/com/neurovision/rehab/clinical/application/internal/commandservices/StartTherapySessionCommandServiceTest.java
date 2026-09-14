package com.neurovision.rehab.clinical.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StartTherapySessionCommandServiceTest {
    private static final UUID SESSION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-14T04:30:00Z");
    private FakePlanRepository plans;
    private FakeSessionRepository sessions;
    private StartTherapySessionCommandService service;

    @BeforeEach
    void setUp() {
        plans = new FakePlanRepository();
        plans.plan = TherapySessionTestData.plan();
        sessions = new FakeSessionRepository();
        service = new StartTherapySessionCommandService(plans, sessions, () -> SESSION_ID,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(2));
    }

    @Test
    void shouldCreateActiveSessionAndDerivePeruQuotaDate() {
        var result = service.handle(command("attempt-1", "visual-search"));

        assertEquals(StartTherapySessionResult.Outcome.CREATED, result.outcome());
        assertEquals(LocalDate.of(2026, 9, 13), result.session().quotaDate());
        assertEquals(NOW.plusSeconds(120), result.session().leaseExpiresAt().orElseThrow());
        assertTrue(sessions.lockAcquired);
    }

    @Test
    void shouldReturnOriginalSessionForSameIdempotencyRequest() {
        var first = service.handle(command("attempt-1", "visual-search"));
        var second = service.handle(command("attempt-1", "visual-search"));

        assertEquals(StartTherapySessionResult.Outcome.IDEMPOTENT_REPLAY, second.outcome());
        assertSame(first.session(), second.session());
        assertEquals(1, sessions.saveCalls);
    }

    @Test
    void shouldRejectIdempotencyKeyReusedWithDifferentRequest() {
        service.handle(command("attempt-1", "visual-search"));
        assertThrows(TherapySessionStartConflictException.class,
                () -> service.handle(command("attempt-1", "fixation")));
    }

    @Test
    void shouldExposeOnlyOwnActiveSessionIdOnNewStartConflict() {
        var active = service.handle(command("attempt-1", "visual-search")).session();
        var conflict = assertThrows(TherapySessionStartConflictException.class,
                () -> service.handle(command("attempt-2", "fixation")));
        assertEquals(active.id(), conflict.activeSessionId().orElseThrow());
        assertEquals(1, sessions.saveCalls);
    }

    @Test
    void shouldRejectExhaustedSessionAndDurationAdmissionLimits() {
        sessions.consumedSlots = 3;
        assertThrows(IllegalStateException.class, () -> service.handle(command("quota", "visual-search")));
        sessions.consumedSlots = 0;
        sessions.exposureSeconds = 26 * 60L;
        assertThrows(IllegalStateException.class, () -> service.handle(command("target", "visual-search")));
        plans.plan = TherapySessionTestData.plan(120, 60);
        sessions.exposureSeconds = 46 * 60L;
        assertThrows(IllegalStateException.class, () -> service.handle(command("hard", "visual-search")));
    }

    @Test
    void shouldKeepExerciseQuotasIndependentAndAllowFlexibleOrder() {
        sessions.consumedExerciseSlots.put(new ExerciseCode("visual-search"), 1L);

        assertThrows(IllegalStateException.class,
                () -> service.handle(command("visual-exhausted", "visual-search")));
        var fixation = service.handle(command("fixation-available", "fixation"));
        assertEquals(new ExerciseCode("fixation"), fixation.session().exerciseCode());
    }

    @Test
    void shouldRejectStaleConfigurationAndWrongPatient() {
        assertThrows(IllegalStateException.class, () -> service.handle(new StartTherapySessionCommand(
                plans.plan.clinicalPatientId(), plans.plan.id(), new ExerciseCode("visual-search"), 6, new IdempotencyKey("stale"))));
        assertThrows(IllegalArgumentException.class, () -> service.handle(new StartTherapySessionCommand(
                new ClinicalPatientId(UUID.randomUUID()), plans.plan.id(), new ExerciseCode("visual-search"), 7, new IdempotencyKey("wrong"))));
    }

    private StartTherapySessionCommand command(String key, String exercise) {
        return new StartTherapySessionCommand(plans.plan.clinicalPatientId(), plans.plan.id(), new ExerciseCode(exercise),
                plans.plan.configurationVersion(), new IdempotencyKey(key));
    }

    private static final class FakePlanRepository implements TherapyPlanRepository {
        private TherapyPlan plan;
        @Override public Optional<TherapyPlan> findById(TherapyPlanId id) { return plan.id().equals(id) ? Optional.of(plan) : Optional.empty(); }
        @Override public void save(TherapyPlan plan) { this.plan = plan; }
    }

    private static final class FakeSessionRepository implements TherapySessionRepository {
        private final List<TherapySession> stored = new ArrayList<>();
        private boolean lockAcquired;
        private long consumedSlots;
        private long exposureSeconds;
        private final Map<ExerciseCode, Long> consumedExerciseSlots = new HashMap<>();
        private int saveCalls;
        @Override public void acquirePatientStartLock(ClinicalPatientId patientId) { lockAcquired = true; }
        @Override public Optional<TherapySession> findByPatientAndIdempotencyKey(ClinicalPatientId patientId, IdempotencyKey key) {
            return stored.stream().filter(s -> s.clinicalPatientId().equals(patientId) && s.idempotencyKey().equals(key)).findFirst();
        }
        @Override public Optional<TherapySession> findActiveByPatient(ClinicalPatientId patientId) {
            return stored.stream().filter(s -> s.clinicalPatientId().equals(patientId) && s.status() == TherapySessionStatus.ACTIVE).findFirst();
        }
        @Override public Optional<TherapySession> findByIdForUpdate(TherapySessionId sessionId) {
            return stored.stream().filter(s -> s.id().equals(sessionId)).findFirst();
        }
        @Override public long countConsumedSlots(TherapyPlanId planId, LocalDate quotaDate) { return consumedSlots; }
        @Override public long countConsumedExerciseSlots(TherapyPlanId planId, LocalDate quotaDate, ExerciseCode exerciseCode) {
            return consumedExerciseSlots.getOrDefault(exerciseCode, 0L);
        }
        @Override public long sumExposureSeconds(TherapyPlanId planId, LocalDate quotaDate) { return exposureSeconds; }
        @Override public void save(TherapySession session) { saveCalls++; stored.add(session); }
    }

    private static final class TherapySessionTestData {
        private static TherapyPlan plan() {
            return plan(30, 60);
        }
        private static TherapyPlan plan(int targetMinutes, Integer hardLimitMinutes) {
            return TherapyPlan.rehydrate(new TherapyPlanId(UUID.fromString("20000000-0000-0000-0000-000000000001")),
                    new ClinicalPatientId(UUID.fromString("30000000-0000-0000-0000-000000000001")),
                    new CognitoSubject("doctor-subject"), TherapyPlanStatus.ACTIVE, 7,
                    3, targetMinutes, hardLimitMinutes, 5, 15, 50,
                    Map.of(new ExerciseCode("visual-search"), 1, new ExerciseCode("fixation"), 2));
        }
    }
}

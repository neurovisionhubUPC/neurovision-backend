package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ListPatientTherapyPlansQueryServiceTest {
    public static <T> T readOnly(Class<T> type, BiFunction<String, Object[], Object> answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> answer.apply(method.getName(), args)));
    }
    public static final class Fixture {
        public static final Instant NOW = Instant.parse("2026-09-14T04:30:00Z");
        public static final LocalDate DAY = LocalDate.of(2026, 9, 13);
        public final ClinicalPatientId patientId = new ClinicalPatientId(new UUID(0, 1));
        public final TherapyPlanId planId = new TherapyPlanId(new UUID(0, 2));
        public final CognitoSubject subject = new CognitoSubject("synthetic-patient");
        public TherapyPlan plan = plan(TherapyPlanStatus.ACTIVE, 60);
        public List<TherapyPlan> entries = List.of(plan);
        public boolean registered = true, assigned = true, missingConfiguration, mismatchedConfiguration;
        public long consumedSlots, consumedExerciseSlots, exposure;
        public int clockReads, activeReads, quotaReads, patientReads, planReads;
        public RuntimeException readFailure;
        public boolean incompatibleTarget;
        public Instant effectiveFrom = NOW.minusSeconds(300);
        public TherapySession active;
        public final Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { throw new AssertionError("Unexpected clock mutation"); }
            public Instant instant() { clockReads++; return NOW; }
        };
        public final ClinicalPatientRepository patients = readOnly(ClinicalPatientRepository.class, (name, args) -> {
            if (!name.equals("findByPatientSubject")) throw new AssertionError("Unexpected patient write");
            patientReads++; assertEquals(subject, args[0]);
            return registered ? Optional.of(ClinicalPatient.registerInvited(patientId, subject, NOW.minusSeconds(600))) : Optional.empty();
        });
        public final TherapyPlanRepository plans = readOnly(TherapyPlanRepository.class, (name, args) -> {
            if (!name.equals("findByClinicalPatientId")) throw new AssertionError("Unexpected plan write/lock");
            planReads++; assertEquals(patientId, args[0]); return entries;
        });
        public final TherapyPlanRevisionRepository revisions = readOnly(TherapyPlanRevisionRepository.class, (name, args) -> {
            if (!name.equals("findEffective")) throw new AssertionError("Unexpected revision write"); return List.of();
        });
        public final DoctorPatientAssignmentRepository assignments = readOnly(DoctorPatientAssignmentRepository.class, (name, args) -> {
            if (!name.equals("findActiveByClinicalPatientIdAndDoctorSubject")) throw new AssertionError("Unexpected assignment write/lock");
            return assigned ? Optional.of(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(new UUID(0, 8)),
                    patientId, new CognitoSubject("synthetic-doctor"), NOW.minusSeconds(600))) : Optional.empty();
        });
        public final TherapeuticExerciseConfigurationRepository configs = readOnly(TherapeuticExerciseConfigurationRepository.class, (name, args) -> {
            if (!name.equals("findEffective")) throw new AssertionError("Unexpected configuration write");
            if (missingConfiguration) return Optional.empty();
            if (incompatibleTarget) return Optional.of(TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(new UUID(0, 3)),
                    planId, TherapeuticExercise.FIXATION, 1, 2, effectiveFrom, new CognitoSubject("synthetic-doctor"), effectiveFrom,
                    new TherapeuticExerciseParameters.Prescribed(TherapeuticConfigurationTestData.fixationSnapshot().parameters(),
                            new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 901))));
            return Optional.of(mismatchedConfiguration || args[1] == TherapeuticExercise.VISUAL_SEARCH
                    ? TherapeuticConfigurationTestData.visualSearchConfiguration(planId, new UUID(0, 3), 1, effectiveFrom)
                    : TherapeuticConfigurationTestData.fixationConfiguration(planId, new UUID(0, 3), 1, effectiveFrom));
        });
        public final TherapySessionRepository sessions = readOnly(TherapySessionRepository.class, (name, args) -> {
            if (name.equals("findActiveByPatient")) { activeReads++; assertEquals(patientId, args[0]); return Optional.ofNullable(active); }
            if (!Set.of("countConsumedSlots", "countConsumedExerciseSlots", "sumExposureSeconds").contains(name))
                throw new AssertionError("Unexpected session write, lock or lookup");
            quotaReads++; assertEquals(DAY, args[1]); if (readFailure != null) throw readFailure;
            return switch (name) { case "countConsumedSlots" -> consumedSlots; case "countConsumedExerciseSlots" -> consumedExerciseSlots;
                default -> exposure; };
        });
        public TherapyPlan plan(TherapyPlanStatus status, Integer safety) {
            return TherapyPlan.rehydrate(planId, patientId, new CognitoSubject("synthetic-doctor"), status, 1,
                    3, 30, safety, 5, 15, 50, Map.of(new ExerciseCode("fixation"), 2));
        }
        public ListPatientTherapyPlansQueryService service() {
            return new ListPatientTherapyPlansQueryService(patients, plans, revisions, assignments, configs, sessions, clock);
        }
        public PatientDailyAvailability availability() { return service().handle(subject).getFirst().dailyAvailability(); }
        public void active(Instant expiry) {
            var config = TherapeuticConfigurationTestData.fixationConfiguration(planId, new UUID(0, 3), 1, NOW.minusSeconds(300));
            active = TherapySession.start(new TherapySessionId(new UUID(0, 4)), plan, new ExerciseCode("fixation"), DAY,
                    TherapeuticConfigurationSnapshot.from(config), new IdempotencyKey("synthetic-start"), "a".repeat(64),
                    expiry.minusSeconds(60), expiry);
        }
    }
    @Test void reportsPersistedDailyBalancesUsingOneLimaClockWithoutWrites() {
        var f = new Fixture(); f.consumedSlots = 1; f.consumedExerciseSlots = 1;
        var a = f.availability(); assertEquals(Fixture.DAY, a.quotaDate()); assertEquals(Fixture.NOW, a.evaluatedAt());
        assertEquals(2L, a.remainingPlanSessionSlots()); assertEquals(1L, a.exercises().getFirst().remainingPrescribedSlots());
        assertEquals("AVAILABLE", a.exercises().getFirst().status().name()); assertNull(a.exercises().getFirst().reason());
        assertEquals(1, f.clockReads); assertEquals(1, f.activeReads); assertEquals(3, f.quotaReads);
    }
    @Test void expiredAndExactlyExpiredActiveLeasesRequireStartRecheckAndNoAccountingReads() {
        for (var expiry : List.of(Fixture.NOW, Fixture.NOW.minusSeconds(1))) {
            var f = new Fixture(); f.active(expiry); var a = f.availability();
            assertNull(a.remainingPlanSessionSlots()); assertNull(a.exercises().getFirst().remainingPrescribedSlots());
            assertEquals("CHECK_AT_START", a.exercises().getFirst().status().name()); assertNull(a.exercises().getFirst().reason());
            assertEquals(0, f.quotaReads); assertEquals(TherapySessionStatus.ACTIVE, f.active.status());
            assertEquals(expiry, f.active.leaseExpiresAt().orElseThrow()); assertTrue(f.active.consumesDailyQuota());
        }
    }
    @Test void livePatientWideSessionBlocksBeforeOtherAdmissionReasons() {
        var f = new Fixture(); f.active(Fixture.NOW.plusSeconds(1)); f.missingConfiguration = true;
        var a = f.availability(); assertEquals("BLOCKED", a.exercises().getFirst().status().name());
        assertEquals("ACTIVE_SESSION", a.exercises().getFirst().reason().name()); assertEquals(3L, a.remainingPlanSessionSlots());
    }
    @Test void missingAndMismatchedEffectiveConfigurationsBlockWithoutChangingOriginalFacts() {
        for (boolean mismatch : List.of(false, true)) {
            var f = new Fixture(); f.missingConfiguration = !mismatch; f.mismatchedConfiguration = mismatch;
            var view = f.service().handle(f.subject).getFirst(); assertEquals(mismatch ? 1 : 0, view.effectiveConfigurations().size());
            assertEquals("CONFIGURATION_UNAVAILABLE", view.dailyAvailability().exercises().getFirst().reason().name());
        }
    }
    @Test void reusesAllQuotaAndTimeBlockersAndClampsLargeCountersWithoutOverflow() {
        String[] reasons = {"PLAN_QUOTA_EXHAUSTED", "EXERCISE_QUOTA_EXHAUSTED", "DAILY_TARGET_TIME_INSUFFICIENT", "DAILY_SAFETY_TIME_INSUFFICIENT"};
        for (int i = 0; i < reasons.length; i++) {
            var f = new Fixture(); if (i == 3) f.entries = List.of(f.plan(TherapyPlanStatus.ACTIVE, 20));
            if (i == 0) f.consumedSlots = Long.MAX_VALUE;
            if (i == 1) f.consumedExerciseSlots = Long.MAX_VALUE;
            if (i >= 2) f.exposure = i == 2 ? 1501 : 301;
            var a = f.availability(); assertEquals("BLOCKED", a.exercises().getFirst().status().name());
            assertEquals(reasons[i], a.exercises().getFirst().reason().name());
            if (i == 0) assertEquals(0L, a.remainingPlanSessionSlots());
            if (i == 1) assertEquals(0L, a.exercises().getFirst().remainingPrescribedSlots());
        }
    }
    @Test void unauthorizedAndInactivePlansAndEmptyListsDoNotReadPatientSessions() {
        for (int i = 0; i < 3; i++) {
            var f = new Fixture(); if (i == 0) f.entries = List.of(); if (i == 1) f.assigned = false;
            if (i == 2) f.entries = List.of(f.plan(TherapyPlanStatus.SUSPENDED, 60));
            assertTrue(f.service().handle(f.subject).isEmpty()); assertEquals(0, f.activeReads); assertEquals(0, f.quotaReads);
        }
    }
    @Test void futureConfigurationsAndImpossibleTimeTargetsAreUnavailable() {
        for (boolean future : List.of(false, true)) {
            var f = new Fixture(); f.incompatibleTarget = !future;
            if (future) f.effectiveFrom = Fixture.NOW.plusSeconds(1);
            assertEquals("CONFIGURATION_UNAVAILABLE", f.availability().exercises().getFirst().reason().name());
        }
    }
    @Test void preservesEveryPrescriptionWithIndependentQuotaAndMatchingConfiguration() {
        var f = new Fixture();
        f.entries = List.of(TherapyPlan.rehydrate(f.planId, f.patientId, new CognitoSubject("synthetic-doctor"),
                TherapyPlanStatus.ACTIVE, 1, 3, 30, 60, 5, 15, 50,
                Map.of(new ExerciseCode("fixation"), 2, new ExerciseCode("visual-search"), 1)));
        var a = f.availability(); assertEquals(2, a.exercises().size()); assertEquals(4, f.quotaReads);
        assertEquals(1, f.clockReads); assertEquals(1, f.activeReads);
        assertEquals(List.of("fixation", "visual-search"), a.exercises().stream().map(e -> e.exerciseCode().value()).toList());
        assertEquals(List.of(2L, 1L), a.exercises().stream().map(PatientDailyAvailability.ExerciseAvailability::remainingPrescribedSlots).toList());
        assertTrue(a.exercises().stream().allMatch(e -> e.status() == PatientDailyAvailability.Status.AVAILABLE));
    }
    @Test void unknownPatientAndReadFailureCannotBecomeInventedAvailability() {
        var unknown = new Fixture(); unknown.registered = false;
        assertThrows(SecurityException.class, () -> unknown.service().handle(unknown.subject)); assertEquals(0, unknown.planReads);
        var failing = new Fixture(); failing.readFailure = new IllegalStateException("synthetic private detail");
        assertThrows(RuntimeException.class, () -> failing.service().handle(failing.subject));
        var corrupt = new Fixture(); corrupt.exposure = -1;
        assertThrows(RuntimeException.class, () -> corrupt.service().handle(corrupt.subject));
    }
}

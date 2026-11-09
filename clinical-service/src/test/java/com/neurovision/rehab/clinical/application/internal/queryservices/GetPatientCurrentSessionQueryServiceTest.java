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

public class GetPatientCurrentSessionQueryServiceTest {
    public static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    /** Only named reads are permitted; writes, locks, accounting and unrelated lookups fail immediately. */
    public static <T> T readOnly(Class<T> type, BiFunction<String, Object[], Object> read) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> "ReadOnlySyntheticPort[redacted]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new AssertionError("Unexpected object operation");
            };
            return read.apply(method.getName(), args);
        }));
    }

    public static final class Fixture {
        public final CognitoSubject subject = new CognitoSubject("synthetic-current-patient");
        public final CognitoSubject doctor = new CognitoSubject("synthetic-current-doctor");
        public final ClinicalPatientId patientId = new ClinicalPatientId(new UUID(41, 1));
        public final TherapyPlanId planId = new TherapyPlanId(new UUID(42, 1));
        public boolean registered = true, assigned = true;
        public int patientReads, sessionReads, assignmentReads, clockReads;
        public String failedRead;
        public TherapySession session = session(patientId, TherapySessionStatus.ACTIVE, NOW.plusSeconds(60), false);
        void failure(String name) { if (name.equals(failedRead)) throw new IllegalStateException("synthetic private cause"); }
        public GetPatientCurrentSessionQueryService service() {
            ClinicalPatientRepository patients = readOnly(ClinicalPatientRepository.class, (name, args) -> {
                if (!name.equals("findByPatientSubject")) throw new AssertionError("Unexpected patient operation");
                patientReads++; assertEquals(subject, args[0]); failure("patient");
                return registered ? Optional.of(ClinicalPatient.registerInvited(patientId, subject, NOW.minusSeconds(3600))) : Optional.empty();
            });
            TherapySessionRepository sessions = readOnly(TherapySessionRepository.class, (name, args) -> {
                if (!name.equals("findActiveByPatient")) throw new AssertionError("Unexpected session operation");
                sessionReads++; assertEquals(patientId, args[0]); failure("session");
                return Optional.ofNullable(session);
            });
            DoctorPatientAssignmentRepository assignments = readOnly(DoctorPatientAssignmentRepository.class, (name, args) -> {
                if (!name.equals("findActiveByClinicalPatientIdAndDoctorSubject")) throw new AssertionError("Unexpected assignment operation");
                assignmentReads++; assertEquals(patientId, args[0]); assertEquals(doctor, args[1]); failure("assignment");
                return assigned ? Optional.of(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(new UUID(43, 1)), patientId, doctor, NOW.minusSeconds(3600))) : Optional.empty();
            });
            Clock clock = new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { throw new AssertionError("Unexpected clock mutation"); }
                public Instant instant() { clockReads++; return NOW; }
            };
            return new GetPatientCurrentSessionQueryService(patients, sessions, assignments, clock);
        }
        public TherapySession session(ClinicalPatientId owner, TherapySessionStatus status, Instant lease, boolean prescribed) {
            var configuration = TherapeuticConfigurationTestData.visualSearchConfiguration(planId, new UUID(44, 1), 1, NOW.minusSeconds(3600));
            if (prescribed) configuration = TherapeuticExerciseConfiguration.create(configuration.id(), planId,
                    TherapeuticExercise.VISUAL_SEARCH, 1, 2, configuration.effectiveFrom(), doctor,
                    configuration.createdAt(), new TherapeuticExerciseParameters.Prescribed(configuration.parameters(),
                            new CompletionTarget(CompletionTarget.Criterion.REPETITIONS, 5)));
            return TherapySession.rehydrate(new TherapySessionId(new UUID(45, 1)), planId, owner, doctor,
                    TherapeuticExercise.VISUAL_SEARCH.code(), TherapeuticConfigurationSnapshot.from(configuration),
                    LocalDate.of(2026, 11, 8), new IdempotencyKey("synthetic-current-start"), "a".repeat(64),
                    1, 15, 10, status, 2, 0, true, NOW.minusSeconds(120), lease,
                    status.isTerminal() ? NOW.minusSeconds(30) : null, null);
        }
    }

    @Test void absentSessionIsNoneAfterPatientAuthorizationWithoutAssignmentLookup() {
        var f = new Fixture(); f.session = null;
        var result = f.service().handle(f.subject);
        assertEquals("NONE", result.state().name()); assertNull(result.session()); assertEquals(NOW, result.evaluatedAt());
        assertEquals(1, f.patientReads); assertEquals(1, f.sessionReads); assertEquals(0, f.assignmentReads); assertEquals(1, f.clockReads);
    }

    @Test void currentSessionReturnsExactFrozenLegacyAndPrescribedFactsWithoutMutation() {
        for (boolean prescribed : List.of(false, true)) {
            var f = new Fixture(); f.session = f.session(f.patientId, TherapySessionStatus.ACTIVE, NOW.plusSeconds(60), prescribed);
            var result = f.service().handle(f.subject);
            assertEquals("ACTIVE", result.state().name()); assertSame(f.session, result.session());
            assertEquals(NOW, result.evaluatedAt()); assertEquals(1, f.clockReads); assertEquals(1, f.assignmentReads);
            assertEquals(TherapySessionStatus.ACTIVE, f.session.status()); assertEquals(NOW.plusSeconds(60), f.session.leaseExpiresAt().orElseThrow());
            assertEquals(2, f.session.activeDurationSeconds()); assertTrue(f.session.consumesDailyQuota());
            assertEquals(prescribed ? 2 : 1, f.session.parameterSchemaVersion());
        }
    }

    @Test void expiredAndExactlyExpiredAreObservationsWithoutRenewalOrClosure() {
        for (Instant expiry : List.of(NOW, NOW.minusSeconds(1))) {
            var f = new Fixture(); f.session = f.session(f.patientId, TherapySessionStatus.ACTIVE, expiry, false);
            var result = f.service().handle(f.subject);
            assertEquals("EXPIRED", result.state().name()); assertEquals(NOW, result.evaluatedAt());
            assertSame(f.session, result.session()); assertEquals(TherapySessionStatus.ACTIVE, f.session.status());
            assertEquals(expiry, f.session.leaseExpiresAt().orElseThrow()); assertTrue(f.session.endedAt().isEmpty());
            assertEquals(2, f.session.activeDurationSeconds()); assertTrue(f.session.consumesDailyQuota());
        }
    }

    @Test void unknownPatientOrEndedAssignmentIsForbiddenNeverFalseAbsence() {
        var unknown = new Fixture(); unknown.registered = false;
        assertThrows(SecurityException.class, () -> unknown.service().handle(unknown.subject));
        assertEquals(0, unknown.sessionReads); assertEquals(0, unknown.assignmentReads);
        var unassigned = new Fixture(); unassigned.assigned = false;
        assertThrows(SecurityException.class, () -> unassigned.service().handle(unassigned.subject));
        assertEquals(1, unassigned.assignmentReads);
    }

    @Test void foreignOrContradictoryRepositoryResultFailsClosed() {
        var foreign = new Fixture(); foreign.session = foreign.session(new ClinicalPatientId(new UUID(41, 2)), TherapySessionStatus.ACTIVE, NOW.plusSeconds(60), false);
        assertThrows(SecurityException.class, () -> foreign.service().handle(foreign.subject)); assertEquals(0, foreign.assignmentReads);
        var terminal = new Fixture(); terminal.session = terminal.session(terminal.patientId, TherapySessionStatus.COMPLETED, null, false);
        assertThrows(IllegalStateException.class, () -> terminal.service().handle(terminal.subject));
    }

    @Test void persistenceFailuresAreNotConvertedToNoSession() {
        for (String stage : List.of("patient", "session", "assignment")) {
            var f = new Fixture(); f.failedRead = stage;
            assertThrows(IllegalStateException.class, () -> f.service().handle(f.subject));
        }
    }
}

package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.application.internal.queryservices.GetPatientCurrentSessionQueryServiceTest.*;

public class GetPatientTherapySessionQueryServiceTest {
    public static final class Fixture {
        public final GetPatientCurrentSessionQueryServiceTest.Fixture frozen = new GetPatientCurrentSessionQueryServiceTest.Fixture();
        public TherapySession source = frozen.session;
        public Detail detail = detail(TherapySessionStatus.ACTIVE, false, false);
        public boolean registered = true;
        public String failedRead;
        public final List<String> calls = new ArrayList<>();

        void read(String name) {
            calls.add(name);
            if (name.equals(failedRead)) throw new IllegalStateException("synthetic private cause");
        }
        public Detail detail(TherapySessionStatus status, boolean prescribed, boolean reached) {
            var s = session(status, prescribed, NOW.plusSeconds(60));
            var p = s.configurationParameters();
            TechnicalProgress progress = TechnicalProgress.notApplicable();
            if (p instanceof TherapeuticExerciseParameters.Prescribed goal) {
                var ids = new HashSet<UUID>();
                int repetitions = reached ? 5 : 2;
                for (int i = 0; i < repetitions; i++) ids.add(new UUID(50, i + 1));
                var state = new SessionActivityProgress(2L * repetitions + 3, 2000, repetitions,
                        SessionActivityProgress.Mode.ACTIVE, NOW, null, null, ids);
                progress = TechnicalProgress.from(goal, state);
            }
            return new Detail(s.clinicalPatientId().value(), new Session(s.id().value(), s.therapyPlanId().value(),
                    s.exerciseCode().value(), s.status().name(), s.startedAt(), s.endedAt().orElse(null),
                    s.activeDurationSeconds(), s.quotaDate(), s.therapeuticConfigurationId().value(),
                    s.configurationVersion(), s.parameterSchemaVersion()), p,
                    new SessionLimits(s.minimumValidDurationMinutes(), s.maximumSessionDurationMinutes(), s.minimumValidSampleCount()), progress);
        }
        public TherapySession session(TherapySessionStatus status, boolean prescribed, java.time.Instant lease) {
            var s = frozen.session(frozen.patientId, TherapySessionStatus.ACTIVE, NOW.plusSeconds(60), prescribed);
            return TherapySession.rehydrate(s.id(), s.therapyPlanId(), s.clinicalPatientId(), s.doctorSubject(),
                    s.exerciseCode(), s.therapeuticConfiguration(), s.quotaDate(), s.idempotencyKey(), s.requestFingerprint(),
                    s.minimumValidDurationMinutes(), s.maximumSessionDurationMinutes(), s.minimumValidSampleCount(),
                    status, s.activeDurationSeconds(), s.validSampleCount(), s.consumesDailyQuota(), s.startedAt(),
                    status.isTerminal() ? null : lease, status.isTerminal() ? NOW.minusSeconds(30) : null,
                    status.isTerminal() && status != TherapySessionStatus.COMPLETED ? "synthetic terminal reason" : null);
        }
        public GetPatientTherapySessionQueryService service() {
            var patients = readOnly(ClinicalPatientRepository.class, (name, args) -> {
                if (!name.equals("findByPatientSubject")) throw new AssertionError("Unexpected patient operation");
                read("patient"); assertEquals(frozen.subject, args[0]);
                return registered ? Optional.of(ClinicalPatient.registerInvited(frozen.patientId, frozen.subject, NOW)) : Optional.empty();
            });
            var sessions = readOnly(TherapySessionRepository.class, (name, args) -> {
                if (!name.equals("findById")) throw new AssertionError("Unexpected session operation or lock");
                read("session"); assertEquals(frozen.session.id(), args[0]); return Optional.ofNullable(source);
            });
            var details = readOnly(DoctorTherapySessionReadRepository.class, (name, args) -> {
                if (!name.equals("observeDetail")) throw new AssertionError("Unexpected detail operation");
                read("detail");
                assertArrayEquals(new Object[]{frozen.patientId.value(), frozen.doctor.value(), frozen.session.id().value()}, args);
                return Optional.ofNullable(detail);
            });
            return new GetPatientTherapySessionQueryService(patients, sessions, details);
        }
        public Detail get() { return service().handle(frozen.subject, frozen.session.id()); }
    }

    @Test void readsFrozenLegacyAndPrescribedDetailsWithoutWritesOrLocks() {
        for (boolean prescribed : List.of(false, true)) {
            var f = new Fixture(); f.detail = f.detail(TherapySessionStatus.ACTIVE, prescribed, false);
            var before = f.source.leaseExpiresAt();
            assertSame(f.detail, f.get());
            assertEquals(List.of("patient", "session", "detail"), f.calls);
            assertEquals(before, f.source.leaseExpiresAt()); assertEquals(TherapySessionStatus.ACTIVE, f.source.status());
            assertEquals(prescribed ? 2 : 1, f.detail.session().parameterSchemaVersion());
        }
    }
    @Test void readsTerminalSessionsAndExpiredActiveWithoutAdmissionOrStateChanges() {
        for (var status : List.of(TherapySessionStatus.ACTIVE, TherapySessionStatus.COMPLETED,
                TherapySessionStatus.ABANDONED, TherapySessionStatus.CANCELLED, TherapySessionStatus.FAILED_TECHNICAL)) {
            var f = new Fixture(); f.source = f.session(status, false, NOW.minusSeconds(1));
            f.detail = f.detail(status, false, false);
            assertEquals(status.name(), f.get().session().status());
            assertEquals(status, f.source.status());
            if (!status.isTerminal()) assertEquals(NOW.minusSeconds(1), f.source.leaseExpiresAt().orElseThrow());
        }
    }
    @Test void retainsExplicitLegacyUnavailabilityAndFrozenTechnicalGoal() {
        var legacy = new Fixture();
        assertEquals(TechnicalProgress.Availability.NOT_APPLICABLE, legacy.get().technicalProgress().availability());
        for (boolean reached : List.of(false, true)) {
            var f = new Fixture(); f.detail = f.detail(reached ? TherapySessionStatus.COMPLETED : TherapySessionStatus.ABANDONED, true, reached);
            var p = f.get().technicalProgress();
            assertEquals(5L, p.target()); assertEquals(2000L, p.activeMilliseconds());
            assertEquals(reached ? 5L : 2L, p.completedRepetitions()); assertEquals(reached, p.targetReached());
        }
    }
    @Test void rejectsUnknownPatientOrSessionBeforeDetailObservation() {
        var f = new Fixture(); f.registered = false;
        assertThrows(SecurityException.class, f::get); assertEquals(List.of("patient"), f.calls);
        f = new Fixture(); f.source = null;
        assertThrows(SecurityException.class, f::get); assertEquals(List.of("patient", "session"), f.calls);
    }
    @Test void rejectsForeignSessionBeforeReadingItsConfigurationAndProgress() {
        var f = new Fixture();
        f.source = f.frozen.session(new ClinicalPatientId(new UUID(41, 2)), TherapySessionStatus.ACTIVE, NOW, false);
        assertThrows(SecurityException.class, f::get); assertEquals(List.of("patient", "session"), f.calls);
    }
    @Test void endedAssignmentOrReassignmentIsUniformDenial() {
        var f = new Fixture(); f.detail = null;
        assertThrows(SecurityException.class, f::get); assertEquals(List.of("patient", "session", "detail"), f.calls);
    }
    @Test void persistenceErrorsStayUnavailableAtEveryRead() {
        for (String read : List.of("patient", "session", "detail")) {
            var f = new Fixture(); f.failedRead = read;
            assertThrows(IllegalStateException.class, f::get);
        }
    }
    @Test void mismatchedReturnedDetailFailsClosed() {
        var f = new Fixture(); var d = f.detail;
        f.detail = new Detail(new UUID(41, 99), d.session(), d.configurationParameters(), d.sessionLimits(), d.technicalProgress());
        assertThrows(IllegalStateException.class, f::get);
        var s = d.session();
        f.detail = new Detail(d.clinicalPatientId(), new Session(new UUID(45, 99), s.therapyPlanId(), s.exerciseCode(),
                s.status(), s.startedAt(), s.endedAt(), s.activeDurationSeconds(), s.quotaDate(), s.configurationId(),
                s.configurationVersion(), s.parameterSchemaVersion()), d.configurationParameters(), d.sessionLimits(), d.technicalProgress());
        assertThrows(IllegalStateException.class, f::get);
    }
}

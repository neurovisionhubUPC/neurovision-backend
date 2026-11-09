package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapySession;
import com.neurovision.rehab.clinical.domain.model.valueobjects.AssignmentStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Discovers one currently stored ACTIVE session without admission, locks or reconciliation. */
public final class GetPatientCurrentSessionQueryService {
    private final ClinicalPatientRepository patients;
    private final TherapySessionRepository sessions;
    private final DoctorPatientAssignmentRepository assignments;
    private final Clock clock;

    public GetPatientCurrentSessionQueryService(ClinicalPatientRepository patients,
            TherapySessionRepository sessions, DoctorPatientAssignmentRepository assignments, Clock clock) {
        this.patients = Objects.requireNonNull(patients);
        this.sessions = Objects.requireNonNull(sessions);
        this.assignments = Objects.requireNonNull(assignments);
        this.clock = Objects.requireNonNull(clock);
    }

    public View handle(CognitoSubject subject) {
        if (subject == null) throw new SecurityException();
        var patient = patients.findByPatientSubject(subject).orElseThrow(SecurityException::new);
        var observation = sessions.findActiveByPatient(patient.id());
        if (observation.isEmpty()) return new View(State.NONE, clock.instant(), null);
        var session = observation.orElseThrow();
        if (!patient.id().equals(session.clinicalPatientId())) throw new SecurityException();
        if (session.status() != TherapySessionStatus.ACTIVE) throw new IllegalStateException("Current session unavailable");
        var assignment = assignments.findActiveByClinicalPatientIdAndDoctorSubject(patient.id(), session.doctorSubject())
                .orElseThrow(SecurityException::new);
        if (!assignment.clinicalPatientId().equals(patient.id())
                || !assignment.doctorSubject().equals(session.doctorSubject())
                || assignment.status() != AssignmentStatus.ACTIVE) throw new SecurityException();
        Instant evaluatedAt = clock.instant();
        Instant expiry = session.leaseExpiresAt().orElseThrow(() -> new IllegalStateException("Current session unavailable"));
        if (evaluatedAt.isBefore(session.startedAt())) throw new IllegalStateException("Current session unavailable");
        return new View(evaluatedAt.isBefore(expiry) ? State.ACTIVE : State.EXPIRED, evaluatedAt, session);
    }

    public enum State { NONE, ACTIVE, EXPIRED }
    public record View(State state, Instant evaluatedAt, TherapySession session) {
        @Override public String toString() { return "PatientCurrentSessionView[redacted]"; }
    }
}

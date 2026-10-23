package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.in.PatientSessionProgressUseCase;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapySession;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Local fulfillment only: this use case never calls Telemetry or derives valid gaze samples. */
public final class PatientSessionProgressService implements PatientSessionProgressUseCase {
    private final ClinicalPatientRepository patients;
    private final TherapySessionRepository sessions;
    private final SessionActivityRepository activity;
    private final Clock clock;
    public PatientSessionProgressService(ClinicalPatientRepository patients, TherapySessionRepository sessions,
            SessionActivityRepository activity, Clock clock) {
        this.patients = Objects.requireNonNull(patients); this.sessions = Objects.requireNonNull(sessions);
        this.activity = Objects.requireNonNull(activity); this.clock = Objects.requireNonNull(clock);
    }
    private TherapySession owned(CognitoSubject patient, TherapySessionId id) {
        var owner = patients.findByPatientSubject(Objects.requireNonNull(patient))
                .orElseThrow(SecurityException::new);
        var session = sessions.findByIdForUpdate(Objects.requireNonNull(id)).orElseThrow(SecurityException::new);
        if (!session.clinicalPatientId().equals(owner.id())) throw new SecurityException();
        target(session); // Legacy sessions never receive an inferred goal or a progress row.
        return session;
    }
    private CompletionTarget target(TherapySession session) {
        if (!(session.configurationParameters() instanceof TherapeuticExerciseParameters.Prescribed prescribed)) {
            throw new SessionProgressConflictException();
        }
        return prescribed.completionTarget();
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private void live(TherapySession session, Instant now) {
        if (session.status() != TherapySessionStatus.ACTIVE || now.isBefore(session.startedAt())
                || !now.isBefore(session.leaseExpiresAt().orElseThrow())) {
            throw new SessionProgressConflictException();
        }
    }
    private SessionActivityProgress state(TherapySessionId id) {
        return activity.find(id).orElseGet(SessionActivityProgress::empty);
    }
    private View view(TherapySession session, SessionActivityProgress progress) {
        var target = target(session);
        return new View(session.id().value(), session.status().name(), target.criterion(), target.target(),
                progress.activeMilliseconds(), progress.completedRepetitions(), target.reached(progress),
                progress.nextSequence(), progress.mode(), session.endedAt().orElse(null));
    }
    @Override public View activity(CognitoSubject patient, TherapySessionId id, SessionActivityEvent event) {
        Objects.requireNonNull(event);
        var session = owned(patient, id);
        var previous = activity.receipt(id, event.eventId());
        if (previous.isPresent()) {
            if (!previous.get().event().equals(event)) throw new SessionProgressConflictException();
            return previous.get().acknowledgement(); // Original receipt, never renewed authorization or lease.
        }
        var now = now(); live(session, now);
        var before = state(id);
        SessionActivityProgress next;
        try { next = before.accept(event, now); }
        catch (IllegalStateException invalidOrder) { throw new SessionProgressConflictException(); }
        if (next.activeMilliseconds() > session.maximumSessionDurationMinutes() * 60_000L) {
            throw new SessionProgressConflictException();
        }
        long seconds = next.activeMilliseconds() / 1000 - before.activeMilliseconds() / 1000;
        var renewed = now.plusSeconds(120);
        var oldLease = session.leaseExpiresAt().orElseThrow();
        if (!renewed.isAfter(oldLease)) renewed = oldLease.plus(1, ChronoUnit.MICROS);
        session.recordAcceptedActivity(Duration.ofSeconds(seconds), 0, now, renewed);
        var result = view(session, next);
        sessions.save(session);
        activity.save(id, next);
        activity.append(id, new SessionActivityRepository.Receipt(event, result));
        return result;
    }
    @Override public View progress(CognitoSubject patient, TherapySessionId id) {
        var session = owned(patient, id);
        return view(session, state(id)); // No implicit expiry, credit, lease renewal or row creation.
    }
    @Override public View complete(CognitoSubject patient, TherapySessionId id, IdempotencyKey key, Action action) {
        Objects.requireNonNull(key); Objects.requireNonNull(action);
        var session = owned(patient, id);
        var previous = activity.completion(id);
        if (previous.isPresent()) {
            var old = previous.get();
            if (!old.key().equals(key.value()) || old.action() != action) throw new SessionProgressConflictException();
            return old.acknowledgement();
        }
        var now = now(); live(session, now);
        var progress = state(id);
        if (action == Action.FINISHED) {
            if (!target(session).reached(progress)) throw new SessionProgressConflictException();
            session.completeWithTechnicalProgress(progress, now);
        } else {
            session.abandon("PATIENT_ABANDONED", now);
        }
        var result = view(session, progress);
        sessions.save(session);
        activity.close(id, new SessionActivityRepository.Completion(key.value(), action, result));
        return result;
    }
}

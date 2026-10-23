package com.neurovision.rehab.clinical.application.ports.out.persistence;

import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CompletionTarget;
import com.neurovision.rehab.clinical.domain.model.valueobjects.SessionActivityProgress;
import java.time.*;
import java.util.*;

/** One read-only observation: authorization, filtered total and bounded rows share a snapshot. */
public interface DoctorTherapySessionReadRepository {
    Observation observe(UUID patient, String doctor, Filters filters, int offset, int limit);

    /** Empty means missing or denied; ownership and current assignment share the detail observation. */
    default Optional<Detail> observeDetail(UUID patient, String doctor, UUID session) {
        throw new UnsupportedOperationException("Session detail observation unavailable");
    }

    record SessionLimits(int minimumValidDurationMinutes, int maximumSessionDurationMinutes, long minimumValidSampleCount) {
        public SessionLimits {
            if(minimumValidDurationMinutes<1 || maximumSessionDurationMinutes<minimumValidDurationMinutes || minimumValidSampleCount<1)
                throw new IllegalArgumentException("Invalid frozen session limits");
        }
    }
    /** Technical accounting only; unavailable legacy goals must never become inferred zeroes. */
    record TechnicalProgress(Availability availability, CompletionTarget.Criterion criterion, Long target,
            Long activeMilliseconds, Long completedRepetitions, Boolean targetReached) {
        public enum Availability { AVAILABLE, NOT_APPLICABLE }
        public TechnicalProgress {
            Objects.requireNonNull(availability);
            if(availability==Availability.NOT_APPLICABLE) {
                if(criterion!=null || target!=null || activeMilliseconds!=null || completedRepetitions!=null || targetReached!=null)
                    throw new IllegalArgumentException("Legacy progress must be absent");
            } else {
                Objects.requireNonNull(criterion); Objects.requireNonNull(target);
                Objects.requireNonNull(activeMilliseconds); Objects.requireNonNull(completedRepetitions); Objects.requireNonNull(targetReached);
                if(target<1 || activeMilliseconds<0 || completedRepetitions<0 || targetReached!=(criterion==CompletionTarget.Criterion.ACTIVE_TIME
                        ? activeMilliseconds/1000>=target : completedRepetitions>=target))
                    throw new IllegalArgumentException("Invalid technical progress");
            }
        }
        public static TechnicalProgress notApplicable() {
            return new TechnicalProgress(Availability.NOT_APPLICABLE,null,null,null,null,null);
        }
        public static TechnicalProgress from(TherapeuticExerciseParameters.Prescribed parameters, SessionActivityProgress state) {
            var goal=parameters.completionTarget();
            return new TechnicalProgress(Availability.AVAILABLE,goal.criterion(),(long)goal.target(),
                    state.activeMilliseconds(),state.completedRepetitions(),goal.reached(state));
        }
    }
    record Detail(UUID clinicalPatientId, Session session, TherapeuticExerciseParameters configurationParameters,
            SessionLimits sessionLimits, TechnicalProgress technicalProgress) {
        public Detail {
            Objects.requireNonNull(clinicalPatientId); Objects.requireNonNull(session);
            Objects.requireNonNull(configurationParameters); Objects.requireNonNull(sessionLimits);
            Objects.requireNonNull(technicalProgress);
            if(!configurationParameters.exercise().code().value().equals(session.exerciseCode()) || session.parameterSchemaVersion()!=configurationParameters.schemaVersion())
                throw new IllegalArgumentException("Inconsistent frozen session configuration");
            if(configurationParameters instanceof TherapeuticExerciseParameters.Prescribed prescribed) {
                if(technicalProgress.availability()!=TechnicalProgress.Availability.AVAILABLE
                        || technicalProgress.criterion()!=prescribed.completionTarget().criterion()
                        || technicalProgress.target()!=(long)prescribed.completionTarget().target()
                        || technicalProgress.activeMilliseconds()/1000!=session.activeDurationSeconds()
                        || ("COMPLETED".equals(session.status()) && !technicalProgress.targetReached()))
                    throw new IllegalArgumentException("Inconsistent technical progress");
            } else if(technicalProgress.availability()!=TechnicalProgress.Availability.NOT_APPLICABLE) {
                throw new IllegalArgumentException("Legacy progress must not be inferred");
            }
        }
    }

    record Filters(String exerciseCode, TherapySessionStatus status, Instant from, Instant to) {}
    record Session(UUID therapySessionId, UUID therapyPlanId, String exerciseCode, String status,
            Instant startedAt, Instant endedAt, long activeDurationSeconds, LocalDate quotaDate,
            UUID configurationId, long configurationVersion, int parameterSchemaVersion) {}
    record Observation(boolean authorized, List<Session> sessions, long total) {
        public Observation {
            sessions=List.copyOf(sessions);
            if(total<0) throw new IllegalArgumentException("Invalid session count");
        }
    }
}

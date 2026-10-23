package com.neurovision.rehab.clinical.application.ports.out.persistence;

import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters;
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
    record Detail(UUID clinicalPatientId, Session session, TherapeuticExerciseParameters configurationParameters, SessionLimits sessionLimits) {
        public Detail {
            Objects.requireNonNull(clinicalPatientId); Objects.requireNonNull(session);
            Objects.requireNonNull(configurationParameters); Objects.requireNonNull(sessionLimits);
            if(!configurationParameters.exercise().code().value().equals(session.exerciseCode()) || session.parameterSchemaVersion()!=configurationParameters.schemaVersion())
                throw new IllegalArgumentException("Inconsistent frozen session configuration");
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

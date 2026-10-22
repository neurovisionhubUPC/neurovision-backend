package com.neurovision.rehab.clinical.application.ports.out.persistence;

import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import java.time.*;
import java.util.*;

/** One read-only observation: authorization, filtered total and bounded rows share a snapshot. */
public interface DoctorTherapySessionReadRepository {
    Observation observe(UUID patient, String doctor, Filters filters, int offset, int limit);

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

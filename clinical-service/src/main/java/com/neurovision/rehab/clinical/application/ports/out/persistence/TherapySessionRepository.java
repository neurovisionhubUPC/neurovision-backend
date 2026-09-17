package com.neurovision.rehab.clinical.application.ports.out.persistence;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapySession;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.LocalDate;
import java.util.Optional;

public interface TherapySessionRepository {
    void acquirePatientStartLock(ClinicalPatientId patientId);
    Optional<TherapySession> findByPatientAndIdempotencyKey(ClinicalPatientId patientId, IdempotencyKey key);
    Optional<TherapySession> findActiveByPatient(ClinicalPatientId patientId);
    Optional<TherapySession> findActiveByPlanForUpdate(TherapyPlanId planId);
    Optional<TherapySession> findById(TherapySessionId sessionId);
    Optional<TherapySession> findByIdForUpdate(TherapySessionId sessionId);
    long countConsumedSlots(TherapyPlanId planId, LocalDate quotaDate);
    long countConsumedExerciseSlots(TherapyPlanId planId, LocalDate quotaDate, ExerciseCode exerciseCode);
    long sumExposureSeconds(TherapyPlanId planId, LocalDate quotaDate);
    void save(TherapySession session);
}

package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.TherapySessionEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.util.UUID;

@ApplicationScoped
public class TherapySessionPanacheRepository implements PanacheRepositoryBase<TherapySessionEntity, UUID> {
    public TherapySessionEntity findByPatientAndIdempotencyKey(UUID patientId, String key) {
        return find("clinicalPatientId = ?1 and idempotencyKey = ?2", patientId, key).firstResult();
    }
    public TherapySessionEntity findActiveByPatient(UUID patientId) {
        return find("clinicalPatientId = ?1 and status = 'ACTIVE'", patientId).firstResult();
    }
    public long countConsumedSlots(UUID planId, LocalDate quotaDate) {
        return count("therapyPlanId = ?1 and quotaDate = ?2 and consumesDailyQuota = true", planId, quotaDate);
    }
    public long countConsumedExerciseSlots(UUID planId, LocalDate quotaDate, String exerciseCode) {
        return count(
                "therapyPlanId = ?1 and quotaDate = ?2 and exerciseCode = ?3 and consumesDailyQuota = true",
                planId,
                quotaDate,
                exerciseCode);
    }
    public long sumExposureSeconds(UUID planId, LocalDate quotaDate) {
        Long total = getEntityManager().createQuery(
                        "select coalesce(sum(s.activeDurationSeconds), 0) from TherapySessionEntity s where s.therapyPlanId = :planId and s.quotaDate = :quotaDate",
                        Long.class)
                .setParameter("planId", planId).setParameter("quotaDate", quotaDate).getSingleResult();
        return total == null ? 0 : total;
    }
}

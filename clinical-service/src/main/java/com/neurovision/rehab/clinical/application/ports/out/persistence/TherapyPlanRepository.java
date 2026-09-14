package com.neurovision.rehab.clinical.application.ports.out.persistence;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanId;
import java.util.Optional;

public interface TherapyPlanRepository {
    Optional<TherapyPlan> findById(TherapyPlanId id);

    /**
     * Acquires the stable plan-row write lock used by configuration version allocation and every
     * mutation of an existing plan. Must be called inside the surrounding use-case transaction.
     */
    Optional<TherapyPlan> findByIdForUpdate(TherapyPlanId id);

    void save(TherapyPlan plan);
}

package com.neurovision.rehab.clinical.application.ports.out.persistence;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanId;
import java.util.Optional;

public interface TherapyPlanRepository {
    Optional<TherapyPlan> findById(TherapyPlanId id);
    void save(TherapyPlan plan);
}

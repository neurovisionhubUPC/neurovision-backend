package com.neurovision.rehab.clinical.application.ports.out.persistence;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanStatus;
import java.time.Instant;
import java.util.*;

/** Read-only, owner-and-current-assignment scoped observations. */
public interface DoctorTherapyPlanReadRepository {
    boolean hasActiveAssignment(UUID patient, String doctor);
    /** Resolve status before count and pagination; order by database UUID ascending. */
    Page page(UUID patient, String doctor, TherapyPlanStatus status, Instant at, int offset, int limit);
    Optional<Detail> detail(UUID patient, UUID plan, String doctor, Instant at);

    record Page(List<TherapyPlan> plans, long total) {
        public Page { plans = List.copyOf(plans); if (total < 0) throw new IllegalArgumentException(); }
    }
    record Detail(TherapyPlan plan, List<TherapeuticExerciseConfiguration> configurations) {
        public Detail { Objects.requireNonNull(plan); configurations = List.copyOf(configurations); }
    }
}

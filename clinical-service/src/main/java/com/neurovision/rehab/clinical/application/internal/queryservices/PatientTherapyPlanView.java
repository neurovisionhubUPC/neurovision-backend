package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;
import java.util.List;

/** Current assigned plan facts; session admission and quota checks remain authoritative at start. */
public record PatientTherapyPlanView(
        TherapyPlan plan, List<TherapeuticExerciseConfiguration> effectiveConfigurations) {
    public PatientTherapyPlanView {
        effectiveConfigurations = List.copyOf(effectiveConfigurations);
    }
}

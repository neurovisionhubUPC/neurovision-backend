package com.neurovision.rehab.clinical.application.ports.out.persistence;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExercise;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanId;
import java.util.OptionalLong;

public interface TherapeuticExerciseConfigurationRepository {
    OptionalLong findLatestVersion(TherapyPlanId therapyPlanId, TherapeuticExercise exercise);

    void append(TherapeuticExerciseConfiguration configuration);
}

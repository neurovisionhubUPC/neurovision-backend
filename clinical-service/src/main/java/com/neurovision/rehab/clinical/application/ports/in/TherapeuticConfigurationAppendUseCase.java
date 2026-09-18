package com.neurovision.rehab.clinical.application.ports.in;

import com.neurovision.rehab.clinical.application.internal.commandservices.AppendTherapeuticConfigurationCommand;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;

public interface TherapeuticConfigurationAppendUseCase {
    TherapeuticExerciseConfiguration handle(AppendTherapeuticConfigurationCommand command);
}

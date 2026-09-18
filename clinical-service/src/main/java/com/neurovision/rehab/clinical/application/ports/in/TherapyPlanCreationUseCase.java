package com.neurovision.rehab.clinical.application.ports.in;

import com.neurovision.rehab.clinical.application.internal.commandservices.CreateTherapyPlanCommand;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;

public interface TherapyPlanCreationUseCase {
    TherapyPlan handle(CreateTherapyPlanCommand command);
}

package com.neurovision.rehab.clinical.application.ports.in;

import com.neurovision.rehab.clinical.application.internal.commandservices.ReviseTherapyPlanCommand;
import com.neurovision.rehab.clinical.application.internal.commandservices.ReviseTherapyPlanResult;

/** Transactional revision boundary; ownership and assignment are checked under the existing locks. */
@FunctionalInterface
public interface TherapyPlanRevisionUseCase {
    ReviseTherapyPlanResult handle(ReviseTherapyPlanCommand command);
}

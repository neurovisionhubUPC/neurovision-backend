package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.in.TherapyPlanRevisionUseCase;
import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorSchedulingEligibilityPort;
import java.util.Objects;

/** Adds scheduling eligibility without duplicating revision, ownership, or assignment rules. */
public final class DoctorTherapyPlanRevisionCommandService {
    private final TherapyPlanRevisionUseCase revisions;
    private final DoctorSchedulingEligibilityPort eligibility;

    public DoctorTherapyPlanRevisionCommandService(
            TherapyPlanRevisionUseCase revisions, DoctorSchedulingEligibilityPort eligibility) {
        this.revisions = Objects.requireNonNull(revisions);
        this.eligibility = Objects.requireNonNull(eligibility);
    }

    public ReviseTherapyPlanResult handle(ReviseTherapyPlanCommand command) {
        Objects.requireNonNull(command);
        // Inspect every scheduling field, not just status: a mixed stop request must not bypass Profile.
        if (command.dailySessionLimit() != null || command.dailyTargetDurationMinutes() != null
                || command.dailyHardSafetyLimitMinutes() != null || command.dailyExercisePrescriptions() != null) {
            eligibility.requireEligible(command.doctorSubject());
        }
        return revisions.handle(command);
    }
}

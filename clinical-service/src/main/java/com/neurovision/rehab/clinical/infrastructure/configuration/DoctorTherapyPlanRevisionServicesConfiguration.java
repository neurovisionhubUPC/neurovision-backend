package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.commandservices.DoctorTherapyPlanRevisionCommandService;
import com.neurovision.rehab.clinical.application.ports.in.TherapyPlanRevisionUseCase;
import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorSchedulingEligibilityPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class DoctorTherapyPlanRevisionServicesConfiguration {
    @Produces
    @ApplicationScoped
    DoctorTherapyPlanRevisionCommandService revisions(
            TherapyPlanRevisionUseCase revisions, DoctorSchedulingEligibilityPort eligibility) {
        return new DoctorTherapyPlanRevisionCommandService(revisions, eligibility);
    }
}

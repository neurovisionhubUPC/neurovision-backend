package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.AssignedPatientQueryService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.AssignedPatientReadPort;
import com.neurovision.rehab.clinical.application.ports.out.integration.AssignedPatientNamesPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class AssignedPatientServicesConfiguration {
    @Produces @ApplicationScoped
    AssignedPatientQueryService assignedPatientService(AssignedPatientReadPort reads,AssignedPatientNamesPort names) { return new AssignedPatientQueryService(reads,names); }
}

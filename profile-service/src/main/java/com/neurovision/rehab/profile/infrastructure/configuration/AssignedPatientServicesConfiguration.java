package com.neurovision.rehab.profile.infrastructure.configuration;

import com.neurovision.rehab.profile.application.internal.queryservices.AssignedPatientNamesService;
import com.neurovision.rehab.profile.application.ports.out.authorization.AssignedPatientNameAuthorizationPort;
import com.neurovision.rehab.profile.application.ports.out.persistence.PatientNameReadPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class AssignedPatientServicesConfiguration {
    @Produces @ApplicationScoped
    AssignedPatientNamesService assignedPatientService(AssignedPatientNameAuthorizationPort authorization,PatientNameReadPort names) { return new AssignedPatientNamesService(authorization,names); }
}

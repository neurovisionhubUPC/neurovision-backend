package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.commandservices.RegisterInvitedClinicalPatientCommandService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.InboxEventRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;
import java.util.UUID;

@ApplicationScoped
public class ApplicationServicesConfiguration {

    @Produces
    @ApplicationScoped
    RegisterInvitedClinicalPatientCommandService registerInvitedClinicalPatientCommandService(
            ClinicalPatientRepository clinicalPatientRepository,
            DoctorPatientAssignmentRepository assignmentRepository,
            InboxEventRepository inboxEventRepository) {
        return new RegisterInvitedClinicalPatientCommandService(
                clinicalPatientRepository,
                assignmentRepository,
                inboxEventRepository,
                UUID::randomUUID,
                UUID::randomUUID,
                Clock.systemUTC());
    }
}

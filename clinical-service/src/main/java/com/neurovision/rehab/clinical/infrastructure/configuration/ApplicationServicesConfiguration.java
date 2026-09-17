package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.commandservices.RegisterInvitedClinicalPatientCommandService;
import com.neurovision.rehab.clinical.application.internal.commandservices.AppendTherapeuticConfigurationCommandService;
import com.neurovision.rehab.clinical.application.internal.commandservices.StartTherapySessionCommandService;
import com.neurovision.rehab.clinical.application.internal.commandservices.TransitionTherapySessionCommandService;
import com.neurovision.rehab.clinical.application.internal.commandservices.CreateTherapyPlanCommandService;
import com.neurovision.rehab.clinical.application.internal.commandservices.ReviseTherapyPlanCommandService;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeTelemetryIngestionQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.TelemetryAuthorizationSnapshotRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.InboxEventRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapyPlanRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapeuticExerciseConfigurationRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapyPlanRevisionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@ApplicationScoped
public class ApplicationServicesConfiguration {

    @Produces
    @ApplicationScoped
    AuthorizeTelemetryIngestionQueryService authorizeTelemetryIngestionQueryService(
            TelemetryAuthorizationSnapshotRepository snapshots) {
        return new AuthorizeTelemetryIngestionQueryService(snapshots, Clock.systemUTC());
    }

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

    @Produces
    @ApplicationScoped
    StartTherapySessionCommandService startTherapySessionCommandService(
            TherapyPlanRepository therapyPlanRepository,
            TherapySessionRepository therapySessionRepository,
            TherapyPlanRevisionRepository revisionRepository,
            DoctorPatientAssignmentRepository assignmentRepository,
            TherapeuticExerciseConfigurationRepository configurationRepository) {
        return new StartTherapySessionCommandService(
                therapyPlanRepository, therapySessionRepository, revisionRepository,
                assignmentRepository, configurationRepository, UUID::randomUUID,
                Clock.systemUTC(), Duration.ofMinutes(2));
    }

    @Produces
    @ApplicationScoped
    TransitionTherapySessionCommandService transitionTherapySessionCommandService(
            TherapySessionRepository therapySessionRepository) {
        return new TransitionTherapySessionCommandService(therapySessionRepository);
    }

    @Produces
    @ApplicationScoped
    AppendTherapeuticConfigurationCommandService appendTherapeuticConfigurationCommandService(
            TherapyPlanRepository therapyPlanRepository,
            DoctorPatientAssignmentRepository assignmentRepository,
            TherapeuticExerciseConfigurationRepository configurationRepository) {
        return new AppendTherapeuticConfigurationCommandService(
                therapyPlanRepository, assignmentRepository, configurationRepository,
                UUID::randomUUID, Clock.systemUTC());
    }

    @Produces
    @ApplicationScoped
    CreateTherapyPlanCommandService createTherapyPlanCommandService(
            DoctorPatientAssignmentRepository assignmentRepository,
            TherapyPlanRepository therapyPlanRepository) {
        return new CreateTherapyPlanCommandService(
                assignmentRepository, therapyPlanRepository, UUID::randomUUID);
    }

    @Produces
    @ApplicationScoped
    ReviseTherapyPlanCommandService reviseTherapyPlanCommandService(
            TherapyPlanRepository therapyPlanRepository,
            TherapyPlanRevisionRepository revisionRepository,
            DoctorPatientAssignmentRepository assignmentRepository,
            TherapySessionRepository therapySessionRepository) {
        return new ReviseTherapyPlanCommandService(
                therapyPlanRepository, revisionRepository, assignmentRepository,
                therapySessionRepository, UUID::randomUUID, Clock.systemUTC());
    }
}

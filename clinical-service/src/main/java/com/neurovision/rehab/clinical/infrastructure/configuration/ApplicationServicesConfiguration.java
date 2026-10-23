package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.commandservices.RegisterInvitedClinicalPatientCommandService;
import com.neurovision.rehab.clinical.application.internal.commandservices.AppendTherapeuticConfigurationCommandService;
import com.neurovision.rehab.clinical.application.internal.commandservices.StartTherapySessionCommandService;
import com.neurovision.rehab.clinical.application.internal.commandservices.StartPatientTherapySessionCommandService;
import com.neurovision.rehab.clinical.application.internal.commandservices.TransitionTherapySessionCommandService;
import com.neurovision.rehab.clinical.application.internal.commandservices.CreateTherapyPlanCommandService;
import com.neurovision.rehab.clinical.application.internal.commandservices.ReviseTherapyPlanCommandService;
import com.neurovision.rehab.clinical.application.internal.commandservices.PatientSessionProgressService;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeTelemetryIngestionQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeHistoricalTelemetryReadQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.ListPatientTherapyPlansQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapyPlanQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.HistoricalTelemetryReadAuthorizationPort;
import com.neurovision.rehab.clinical.application.ports.out.authorization.TelemetryAuthorizationSnapshotRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapyPlanReadRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.InboxEventRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapyPlanRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapeuticExerciseConfigurationRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapyPlanRevisionRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import java.time.Clock;
import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorSchedulingEligibilityPort;
import java.time.Duration;
import java.util.UUID;

@ApplicationScoped
@RegisterForReflection(targets = {
        TherapeuticExerciseParameters.VisualScanning.class,
        TherapeuticExerciseParameters.VisualSearch.class,
        TherapeuticExerciseParameters.Fixation.class
})
public class ApplicationServicesConfiguration {
    @Produces
    @ApplicationScoped
    @Typed(PatientSessionProgressService.class)
    com.neurovision.rehab.clinical.application.internal.commandservices.PatientSessionProgressService patientSessionProgressService(
            ClinicalPatientRepository patients, TherapySessionRepository sessions,
            com.neurovision.rehab.clinical.application.ports.out.persistence.SessionActivityRepository activity) {
        return new com.neurovision.rehab.clinical.application.internal.commandservices.PatientSessionProgressService(
                patients, sessions, activity, Clock.systemUTC());
    }

    @Produces
    @ApplicationScoped
    DoctorTherapyPlanQueryService doctorTherapyPlanQueryService(DoctorTherapyPlanReadRepository reads) {
        return new DoctorTherapyPlanQueryService(reads, Clock.systemUTC());
    }

    @Produces
    @ApplicationScoped
    StartPatientTherapySessionCommandService startPatientTherapySessionCommandService(
            ClinicalPatientRepository patients, StartTherapySessionCommandService starter) {
        return new StartPatientTherapySessionCommandService(patients, starter);
    }

    @Produces
    @ApplicationScoped
    ListPatientTherapyPlansQueryService listPatientTherapyPlansQueryService(
            ClinicalPatientRepository patients, TherapyPlanRepository plans,
            TherapyPlanRevisionRepository revisions, DoctorPatientAssignmentRepository assignments,
            TherapeuticExerciseConfigurationRepository configurations) {
        return new ListPatientTherapyPlansQueryService(patients, plans, revisions, assignments,
                configurations, Clock.systemUTC());
    }

    @Produces
    @ApplicationScoped
    AuthorizeHistoricalTelemetryReadQueryService authorizeHistoricalTelemetryReadQueryService(
            HistoricalTelemetryReadAuthorizationPort authorization) {
        return new AuthorizeHistoricalTelemetryReadQueryService(authorization);
    }

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
            TherapeuticExerciseConfigurationRepository configurationRepository,
            DoctorSchedulingEligibilityPort eligibility) {
        return new AppendTherapeuticConfigurationCommandService(
                therapyPlanRepository, assignmentRepository, configurationRepository,
                UUID::randomUUID, Clock.systemUTC(), eligibility);
    }

    @Produces
    @ApplicationScoped
    CreateTherapyPlanCommandService createTherapyPlanCommandService(
            DoctorPatientAssignmentRepository assignmentRepository,
            TherapyPlanRepository therapyPlanRepository, DoctorSchedulingEligibilityPort eligibility) {
        return new CreateTherapyPlanCommandService(
                assignmentRepository, therapyPlanRepository, UUID::randomUUID, eligibility);
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

package com.neurovision.rehab.iam.infrastructure.configuration;

import com.neurovision.rehab.iam.application.internal.commandservices.ActivateDoctorInvitationCommandService;
import com.neurovision.rehab.iam.application.internal.commandservices.InvitePatientCommandService;
import com.neurovision.rehab.iam.application.internal.commandservices.ActivatePatientInvitationCommandService;
import com.neurovision.rehab.iam.application.internal.commandservices.InviteDoctorCommandService;
import com.neurovision.rehab.iam.application.internal.queryservices.GetDoctorPatientInvitationsQueryService;
import com.neurovision.rehab.iam.application.ports.out.identity.DoctorIdentityProvider;
import com.neurovision.rehab.iam.application.ports.out.identity.PatientIdentityProvider;
import com.neurovision.rehab.iam.application.ports.out.messaging.DoctorInvitationOutbox;
import com.neurovision.rehab.iam.application.ports.out.persistence.DoctorInvitationRepository;
import com.neurovision.rehab.iam.application.ports.out.persistence.PatientInvitationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;
import java.util.UUID;

@ApplicationScoped
public class ApplicationServicesConfiguration {

    @Produces
    @ApplicationScoped
    ActivateDoctorInvitationCommandService activateDoctorInvitationCommandService(
            DoctorInvitationRepository invitationRepository) {
        return new ActivateDoctorInvitationCommandService(invitationRepository);
    }

    @Produces
    @ApplicationScoped
    InviteDoctorCommandService inviteDoctorCommandService(
            DoctorInvitationRepository invitationRepository,
            DoctorInvitationOutbox invitationOutbox,
            DoctorIdentityProvider identityProvider) {
        return new InviteDoctorCommandService(
                invitationRepository,
                invitationOutbox,
                identityProvider,
                UUID::randomUUID,
                Clock.systemUTC());
    }

    @Produces
    @ApplicationScoped
    ActivatePatientInvitationCommandService activatePatientInvitationCommandService(
            PatientInvitationRepository invitationRepository) {
        return new ActivatePatientInvitationCommandService(invitationRepository);
    }

    @Produces
    @ApplicationScoped
    InvitePatientCommandService invitePatientCommandService(
            PatientInvitationRepository invitationRepository,
            PatientIdentityProvider identityProvider) {
        return new InvitePatientCommandService(
                invitationRepository, identityProvider, Clock.systemUTC());
    }

    @Produces
    @ApplicationScoped
    GetDoctorPatientInvitationsQueryService getDoctorPatientInvitationsQueryService(
            PatientInvitationRepository invitationRepository) {
        return new GetDoctorPatientInvitationsQueryService(invitationRepository);
    }
}

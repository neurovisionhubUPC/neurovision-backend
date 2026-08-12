package com.neurovision.rehab.iam.infrastructure.configuration;

import com.neurovision.rehab.iam.application.internal.commandservices.InvitePatientCommandService;
import com.neurovision.rehab.iam.application.internal.queryservices.GetDoctorPatientInvitationsQueryService;
import com.neurovision.rehab.iam.application.ports.out.identity.PatientIdentityProvider;
import com.neurovision.rehab.iam.application.ports.out.persistence.PatientInvitationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

@ApplicationScoped
public class ApplicationServicesConfiguration {

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

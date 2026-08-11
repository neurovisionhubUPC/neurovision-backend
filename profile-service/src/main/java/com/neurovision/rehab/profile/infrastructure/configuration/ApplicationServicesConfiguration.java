package com.neurovision.rehab.profile.infrastructure.configuration;

import com.neurovision.rehab.profile.application.internal.commandservices.RegisterInvitedPatientCommandService;
import com.neurovision.rehab.profile.application.ports.out.persistence.InboxEventRepository;
import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;
import java.util.UUID;

@ApplicationScoped
public class ApplicationServicesConfiguration {

    @Produces
    @ApplicationScoped
    RegisterInvitedPatientCommandService registerInvitedPatientCommandService(
            UserProfileRepository profileRepository, InboxEventRepository inboxRepository) {
        return new RegisterInvitedPatientCommandService(
                profileRepository, inboxRepository, UUID::randomUUID, Clock.systemUTC());
    }
}

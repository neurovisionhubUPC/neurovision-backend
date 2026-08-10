package com.neurovision.rehab.iam.application.internal.commandservices;

import com.neurovision.rehab.iam.application.ports.out.identity.PatientIdentityProvider;
import com.neurovision.rehab.iam.application.ports.out.identity.ProvisionedPatientIdentity;
import com.neurovision.rehab.iam.application.ports.out.messaging.PatientAccountInvitedV1;
import com.neurovision.rehab.iam.application.ports.out.persistence.PatientInvitationRepository;
import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.commands.InvitePatientCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public class InvitePatientCommandService {

    private static final String IDENTITY_PROVISIONING_FAILURE = "IDENTITY_PROVISIONING_FAILED";

    private final PatientInvitationRepository invitationRepository;
    private final PatientIdentityProvider identityProvider;
    private final Clock clock;

    public InvitePatientCommandService(
            PatientInvitationRepository invitationRepository,
            PatientIdentityProvider identityProvider,
            Clock clock) {
        this.invitationRepository = Objects.requireNonNull(invitationRepository);
        this.identityProvider = Objects.requireNonNull(identityProvider);
        this.clock = Objects.requireNonNull(clock);
    }

    public PatientInvitation handle(InvitePatientCommand command) {
        Objects.requireNonNull(command, "Invite patient command must not be null");
        if (invitationRepository.existsActiveByPatientEmail(command.patientEmail())) {
            throw new PatientAlreadyInvitedException();
        }

        var invitation = PatientInvitation.initiate(
                new InvitationId(UUID.randomUUID()),
                command.invitedByDoctor(),
                command.patientEmail(),
                clock.instant());
        invitationRepository.save(invitation);

        final var provisionedIdentity = provisionIdentity(invitation);
        invitation.markSent(provisionedIdentity.patientSubject(), clock.instant());
        var outboxEvents = invitation.pullDomainEvents().stream()
                .map(PatientAccountInvitedV1::from)
                .toList();
        invitationRepository.saveWithOutboxEvents(invitation, outboxEvents);
        return invitation;
    }

    private ProvisionedPatientIdentity provisionIdentity(PatientInvitation invitation) {
        try {
            return identityProvider.invitePatient(invitation.patientEmail());
        } catch (RuntimeException failure) {
            invitation.markFailed(IDENTITY_PROVISIONING_FAILURE, clock.instant());
            invitationRepository.save(invitation);
            throw new PatientInvitationProvisioningException(failure);
        }
    }
}

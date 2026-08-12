package com.neurovision.rehab.iam.application.internal.commandservices;

import com.neurovision.rehab.iam.application.ports.out.identity.DoctorIdentityProvider;
import com.neurovision.rehab.iam.application.ports.out.identity.ProvisionedDoctorIdentity;
import com.neurovision.rehab.iam.application.ports.out.messaging.DoctorAccountInvitedV1;
import com.neurovision.rehab.iam.application.ports.out.messaging.DoctorInvitationOutbox;
import com.neurovision.rehab.iam.application.ports.out.persistence.DoctorInvitationRepository;
import com.neurovision.rehab.iam.domain.model.aggregates.DoctorInvitation;
import com.neurovision.rehab.iam.domain.model.commands.InviteDoctorCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class InviteDoctorCommandService {

    private static final String IDENTITY_PROVISIONING_FAILURE = "IDENTITY_PROVISIONING_FAILED";

    private final DoctorInvitationRepository invitations;
    private final DoctorInvitationOutbox outbox;
    private final DoctorIdentityProvider identityProvider;
    private final Supplier<UUID> invitationIdGenerator;
    private final Clock clock;

    public InviteDoctorCommandService(
            DoctorInvitationRepository invitations,
            DoctorInvitationOutbox outbox,
            DoctorIdentityProvider identityProvider,
            Supplier<UUID> invitationIdGenerator,
            Clock clock) {
        this.invitations = Objects.requireNonNull(invitations);
        this.outbox = Objects.requireNonNull(outbox);
        this.identityProvider = Objects.requireNonNull(identityProvider);
        this.invitationIdGenerator = Objects.requireNonNull(invitationIdGenerator);
        this.clock = Objects.requireNonNull(clock);
    }

    public DoctorInvitation handle(InviteDoctorCommand command) {
        Objects.requireNonNull(command, "Invite doctor command must not be null");
        if (invitations.existsActiveByDoctorEmail(command.doctorEmail())) {
            throw new DoctorAlreadyInvitedException();
        }

        var invitation = DoctorInvitation.initiate(
                new InvitationId(invitationIdGenerator.get()),
                command.invitedByAdministrator(),
                command.doctorEmail(),
                clock.instant());
        invitations.save(invitation);

        var provisionedIdentity = provisionIdentity(invitation);
        invitation.markInvited(provisionedIdentity.doctorSubject(), clock.instant());
        var integrationEvents = invitation.pullDomainEvents().stream()
                .map(DoctorAccountInvitedV1::from)
                .toList();
        outbox.saveAtomically(invitation, integrationEvents);
        return invitation;
    }

    private ProvisionedDoctorIdentity provisionIdentity(DoctorInvitation invitation) {
        try {
            return identityProvider.inviteDoctor(invitation.doctorEmail());
        } catch (RuntimeException failure) {
            invitation.markFailed(IDENTITY_PROVISIONING_FAILURE, clock.instant());
            invitations.save(invitation);
            throw new DoctorInvitationProvisioningException(failure);
        }
    }
}

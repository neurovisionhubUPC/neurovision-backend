package com.neurovision.rehab.iam.application.internal.commandservices;

import com.neurovision.rehab.iam.application.ports.out.persistence.DoctorInvitationRepository;
import com.neurovision.rehab.iam.domain.model.aggregates.DoctorInvitation;
import com.neurovision.rehab.iam.domain.model.commands.ActivateDoctorInvitationCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import java.util.Objects;

public final class ActivateDoctorInvitationCommandService {

    private final DoctorInvitationRepository invitations;

    public ActivateDoctorInvitationCommandService(DoctorInvitationRepository invitations) {
        this.invitations = Objects.requireNonNull(invitations);
    }

    public DoctorInvitation handle(ActivateDoctorInvitationCommand command) {
        Objects.requireNonNull(command, "Activate doctor invitation command must not be null");
        var invitation = invitations
                .findByDoctorSubject(command.doctorSubject())
                .orElseThrow(DoctorInvitationNotFoundException::new);
        if (invitation.status() == InvitationStatus.ACTIVATED) {
            return invitation;
        }
        invitation.markActivated(command.authenticatedAt());
        invitations.save(invitation);
        return invitation;
    }
}

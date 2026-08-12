package com.neurovision.rehab.iam.application.internal.commandservices;

import com.neurovision.rehab.iam.application.ports.out.persistence.PatientInvitationRepository;
import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.commands.ActivatePatientInvitationCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import java.util.Objects;

public final class ActivatePatientInvitationCommandService {

    private final PatientInvitationRepository invitations;

    public ActivatePatientInvitationCommandService(PatientInvitationRepository invitations) {
        this.invitations = Objects.requireNonNull(invitations);
    }

    public PatientInvitation handle(ActivatePatientInvitationCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        var invitation = invitations
                .findByPatientSubject(command.patientSubject())
                .orElseThrow(PatientInvitationNotFoundException::new);
        if (invitation.status() == InvitationStatus.ACTIVATED) {
            return invitation;
        }
        invitation.markActivated(command.authenticatedAt());
        invitations.save(invitation);
        return invitation;
    }
}

package com.neurovision.rehab.iam.interfaces.rest.transform;

import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.commands.InvitePatientCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.interfaces.rest.resource.InvitePatientRequest;
import com.neurovision.rehab.iam.interfaces.rest.resource.PatientInvitationResponse;

public final class PatientInvitationRestAssembler {

    private PatientInvitationRestAssembler() {}

    public static InvitePatientCommand toCommand(
            InvitePatientRequest request, String doctorSubject) {
        return new InvitePatientCommand(
                new EmailAddress(request.patientEmail()), new CognitoSubject(doctorSubject));
    }

    public static PatientInvitationResponse toResponse(PatientInvitation invitation) {
        return new PatientInvitationResponse(
                invitation.id().value(),
                invitation.patientEmail().value(),
                invitation.patientSubject().orElseThrow().value(),
                invitation.status().name(),
                invitation.createdAt(),
                invitation.updatedAt());
    }
}

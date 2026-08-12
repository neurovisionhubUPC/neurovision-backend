package com.neurovision.rehab.iam.interfaces.rest.transform;

import com.neurovision.rehab.iam.domain.model.aggregates.DoctorInvitation;
import com.neurovision.rehab.iam.domain.model.commands.InviteDoctorCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.interfaces.rest.resource.DoctorInvitationResponse;
import com.neurovision.rehab.iam.interfaces.rest.resource.InviteDoctorRequest;

public final class DoctorInvitationRestAssembler {

    private DoctorInvitationRestAssembler() {}

    public static InviteDoctorCommand toCommand(
            InviteDoctorRequest request, String administratorSubject) {
        return new InviteDoctorCommand(
                new EmailAddress(request.doctorEmail()),
                new CognitoSubject(administratorSubject));
    }

    public static DoctorInvitationResponse toResponse(DoctorInvitation invitation) {
        return new DoctorInvitationResponse(
                invitation.id().value(),
                invitation.doctorEmail().value(),
                invitation.doctorSubject().map(CognitoSubject::value).orElse(null),
                invitation.status().name(),
                invitation.createdAt(),
                invitation.updatedAt(),
                invitation.activatedAt().orElse(null));
    }
}

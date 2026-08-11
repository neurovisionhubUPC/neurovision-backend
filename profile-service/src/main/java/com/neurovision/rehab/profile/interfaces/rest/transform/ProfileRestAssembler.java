package com.neurovision.rehab.profile.interfaces.rest.transform;

import com.neurovision.rehab.profile.application.internal.commandservices.CompleteCurrentProfileCommand;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import com.neurovision.rehab.profile.interfaces.rest.resource.CompleteProfileRequest;
import com.neurovision.rehab.profile.interfaces.rest.resource.ProfileResponse;

public final class ProfileRestAssembler {

    private ProfileRestAssembler() {}

    public static CompleteCurrentProfileCommand toCommand(
            CompleteProfileRequest request, String cognitoSubject) {
        return new CompleteCurrentProfileCommand(
                cognitoSubject,
                request.firstName(),
                request.lastName(),
                request.documentNumber(),
                request.birthDate(),
                request.phone());
    }

    public static ProfileResponse toResponse(UserProfile profile) {
        var details = profile.personalDetails().orElse(null);
        return new ProfileResponse(
                profile.id().value(),
                profile.type().name(),
                profile.status().name(),
                details == null ? null : details.firstName(),
                details == null ? null : details.lastName(),
                details == null ? null : details.documentNumber(),
                details == null ? null : details.birthDate(),
                details == null ? null : details.phone(),
                profile.createdAt(),
                profile.updatedAt());
    }
}

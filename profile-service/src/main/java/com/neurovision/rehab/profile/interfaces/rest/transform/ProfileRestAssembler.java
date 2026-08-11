package com.neurovision.rehab.profile.interfaces.rest.transform;

import com.neurovision.rehab.profile.application.internal.commandservices.CompleteCurrentProfileCommand;
import com.neurovision.rehab.profile.application.internal.commandservices.OnboardDoctorCommand;
import com.neurovision.rehab.profile.application.internal.queryservices.CurrentProfile;
import com.neurovision.rehab.profile.interfaces.rest.resource.CompleteProfileRequest;
import com.neurovision.rehab.profile.interfaces.rest.resource.OnboardDoctorRequest;
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

    public static OnboardDoctorCommand toCommand(
            OnboardDoctorRequest request, String cognitoSubject) {
        return new OnboardDoctorCommand(
                cognitoSubject,
                request.firstName(),
                request.lastName(),
                request.documentNumber(),
                request.birthDate(),
                request.phone(),
                request.specialty(),
                request.medicalLicense());
    }

    public static ProfileResponse toResponse(CurrentProfile currentProfile) {
        var profile = currentProfile.userProfile();
        var details = profile.personalDetails().orElse(null);
        var professionalDetails = currentProfile.doctorProfile()
                .map(doctorProfile -> doctorProfile.professionalDetails())
                .orElse(null);
        return new ProfileResponse(
                profile.id().value(),
                profile.type().name(),
                profile.status().name(),
                details == null ? null : details.firstName(),
                details == null ? null : details.lastName(),
                details == null ? null : details.documentNumber(),
                details == null ? null : details.birthDate(),
                details == null ? null : details.phone(),
                professionalDetails == null ? null : professionalDetails.specialty(),
                professionalDetails == null ? null : professionalDetails.medicalLicense(),
                profile.createdAt(),
                profile.updatedAt());
    }
}

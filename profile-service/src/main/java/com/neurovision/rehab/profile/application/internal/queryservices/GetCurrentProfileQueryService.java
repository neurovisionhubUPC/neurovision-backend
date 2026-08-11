package com.neurovision.rehab.profile.application.internal.queryservices;

import com.neurovision.rehab.profile.application.internal.commandservices.ProfileNotFoundException;
import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import com.neurovision.rehab.profile.application.ports.out.persistence.DoctorProfileRepository;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileType;
import java.util.Objects;

public final class GetCurrentProfileQueryService {

    private final UserProfileRepository profiles;
    private final DoctorProfileRepository doctorProfiles;

    public GetCurrentProfileQueryService(
            UserProfileRepository profiles, DoctorProfileRepository doctorProfiles) {
        this.profiles = Objects.requireNonNull(profiles);
        this.doctorProfiles = Objects.requireNonNull(doctorProfiles);
    }

    public CurrentProfile handle(GetCurrentProfileQuery query) {
        Objects.requireNonNull(query, "Query must not be null");
        var profile = profiles.findByCognitoSubject(new CognitoSubject(query.cognitoSubject()))
                .orElseThrow(ProfileNotFoundException::new);
        if (profile.type() == ProfileType.DOCTOR) {
            return new CurrentProfile(profile, doctorProfiles.findByProfileId(profile.id()));
        }
        return CurrentProfile.withoutProfessionalDetails(profile);
    }
}

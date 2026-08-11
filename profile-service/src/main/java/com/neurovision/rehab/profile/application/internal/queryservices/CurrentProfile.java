package com.neurovision.rehab.profile.application.internal.queryservices;

import com.neurovision.rehab.profile.domain.model.aggregates.DoctorProfile;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import java.util.Objects;
import java.util.Optional;

public record CurrentProfile(UserProfile userProfile, Optional<DoctorProfile> doctorProfile) {

    public CurrentProfile {
        Objects.requireNonNull(userProfile, "User profile must not be null");
        Objects.requireNonNull(doctorProfile, "Doctor profile optional must not be null");
    }

    public static CurrentProfile withoutProfessionalDetails(UserProfile userProfile) {
        return new CurrentProfile(userProfile, Optional.empty());
    }

    public static CurrentProfile withProfessionalDetails(
            UserProfile userProfile, DoctorProfile doctorProfile) {
        return new CurrentProfile(userProfile, Optional.of(doctorProfile));
    }
}

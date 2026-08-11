package com.neurovision.rehab.profile.application.ports.out.persistence;

import com.neurovision.rehab.profile.domain.model.aggregates.DoctorProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import java.util.Optional;

public interface DoctorProfileRepository {

    Optional<DoctorProfile> findByProfileId(ProfileId profileId);

    void save(DoctorProfile profile);
}

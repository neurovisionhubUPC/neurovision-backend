package com.neurovision.rehab.profile.application.ports.out.persistence;

import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import java.util.Optional;

public interface UserProfileRepository {

    Optional<UserProfile> findByCognitoSubject(CognitoSubject cognitoSubject);

    void save(UserProfile profile);
}

package com.neurovision.rehab.profile.application.internal.queryservices;

import com.neurovision.rehab.profile.application.internal.commandservices.ProfileNotFoundException;
import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import java.util.Objects;

public final class GetCurrentProfileQueryService {

    private final UserProfileRepository profiles;

    public GetCurrentProfileQueryService(UserProfileRepository profiles) {
        this.profiles = Objects.requireNonNull(profiles);
    }

    public UserProfile handle(GetCurrentProfileQuery query) {
        Objects.requireNonNull(query, "Query must not be null");
        return profiles.findByCognitoSubject(new CognitoSubject(query.cognitoSubject()))
                .orElseThrow(ProfileNotFoundException::new);
    }
}

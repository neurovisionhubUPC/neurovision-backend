package com.neurovision.rehab.profile.application.internal.commandservices;

import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.PersonalDetails;
import java.time.Clock;
import java.util.Objects;

public final class CompleteCurrentProfileCommandService {

    private final UserProfileRepository profiles;
    private final Clock clock;

    public CompleteCurrentProfileCommandService(UserProfileRepository profiles, Clock clock) {
        this.profiles = Objects.requireNonNull(profiles);
        this.clock = Objects.requireNonNull(clock);
    }

    public UserProfile handle(CompleteCurrentProfileCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        var profile = profiles.findByCognitoSubject(new CognitoSubject(command.cognitoSubject()))
                .orElseThrow(ProfileNotFoundException::new);
        profile.complete(
                new PersonalDetails(
                        command.firstName(),
                        command.lastName(),
                        command.documentNumber(),
                        command.birthDate(),
                        command.phone()),
                clock.instant());
        profiles.save(profile);
        return profile;
    }
}

package com.neurovision.rehab.profile.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompleteCurrentProfileCommandServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-10T21:04:30Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-10T21:05:30Z");

    @Test
    void shouldCompleteTheProfileBelongingToTheAuthenticatedSubject() {
        var repository = new SingleProfileRepository(pendingProfile());
        var service = new CompleteCurrentProfileCommandService(
                repository, Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));

        var profile = service.handle(command("patient-cognito-sub"));

        assertEquals(ProfileStatus.ACTIVE, profile.status());
        assertEquals("Andrea", profile.personalDetails().orElseThrow().firstName());
        assertEquals(profile, repository.profile);
    }

    @Test
    void shouldRejectAnUnknownAuthenticatedSubject() {
        var service = new CompleteCurrentProfileCommandService(
                new SingleProfileRepository(null), Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));

        assertThrows(ProfileNotFoundException.class, () -> service.handle(command("unknown-sub")));
    }

    private static CompleteCurrentProfileCommand command(String subject) {
        return new CompleteCurrentProfileCommand(
                subject,
                "Andrea",
                "Salazar",
                "74859621",
                LocalDate.of(1998, 4, 12),
                "+51 987 654 321");
    }

    private static UserProfile pendingProfile() {
        return UserProfile.registerInvitedPatient(
                new ProfileId(UUID.fromString("1f665873-4b23-4720-9b82-f13f334702ff")),
                new CognitoSubject("patient-cognito-sub"),
                CREATED_AT);
    }

    private static final class SingleProfileRepository implements UserProfileRepository {

        private UserProfile profile;

        private SingleProfileRepository(UserProfile profile) {
            this.profile = profile;
        }

        @Override
        public Optional<UserProfile> findByCognitoSubject(CognitoSubject cognitoSubject) {
            return Optional.ofNullable(profile)
                    .filter(current -> current.cognitoSubject().equals(cognitoSubject));
        }

        @Override
        public void save(UserProfile profile) {
            this.profile = profile;
        }
    }
}

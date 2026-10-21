package com.neurovision.rehab.profile.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.profile.application.internal.commandservices.CompleteCurrentProfileCommandService;
import com.neurovision.rehab.profile.application.internal.commandservices.OnboardDoctorCommand;
import com.neurovision.rehab.profile.application.internal.commandservices.OnboardDoctorCommandService;
import com.neurovision.rehab.profile.application.internal.commandservices.ProfileTypeConflictException;
import com.neurovision.rehab.profile.application.internal.queryservices.GetCurrentProfileQueryService;
import com.neurovision.rehab.profile.application.ports.out.persistence.DoctorProfileRepository;
import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import com.neurovision.rehab.profile.domain.model.aggregates.DoctorProfile;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.infrastructure.persistence.jpa.transaction.TransactionalCurrentProfileService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.BadRequestException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

/** REST boundary tests with real command/domain validation and in-memory persistence. */
class DoctorOnboardingValidationBoundaryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
    private final Users users = new Users();
    private final Doctors doctors = new Doctors();
    private final OnboardDoctorCommandService onboarding = new OnboardDoctorCommandService(
            users, doctors, UUID::randomUUID, UUID::randomUUID, CLOCK);

    @Test
    void malformedCmpReturns400WithoutMutatingOrSavingTheExistingProfile() {
        seed();
        var personalBefore = users.profile.personalDetails();
        var professionalBefore = doctors.profile.professionalDetails();
        var failure = assertThrows(BadRequestException.class,
                () -> controller().onboardDoctor(request("Changed", "12#45*")));

        assertEquals(400, failure.getResponse().getStatus());
        assertEquals("Invalid doctor profile request", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(personalBefore, users.profile.personalDetails());
        assertEquals(professionalBefore, doctors.profile.professionalDetails());
        assertEquals(0, users.saves);
        assertEquals(0, doctors.saves);
    }

    @Test
    void validCmpStillCompletesTheAuthenticatedDoctorsProfile() {
        var response = controller().onboardDoctor(request("Synthetic", "012345"));

        assertEquals("DOCTOR", response.profileType());
        assertEquals("ACTIVE", response.status());
        assertEquals("012345", response.medicalLicense());
        assertEquals("doctor-sub", users.profile.cognitoSubject().value());
        assertEquals(1, users.saves);
        assertEquals(1, doctors.saves);
    }

    @Test
    void completionStillAllowsAnAbsentCmp() {
        var response = controller().onboardDoctor(request("Synthetic", null));

        assertEquals("ACTIVE", response.status());
        assertNull(response.medicalLicense());
        assertEquals(1, users.saves);
        assertEquals(1, doctors.saves);
    }

    @Test
    void profileConflictsKeepTheirExisting409Mapping() {
        users.profile = UserProfile.registerInvitedPatient(
                new ProfileId(UUID.randomUUID()), new CognitoSubject("doctor-sub"), CLOCK.instant());
        var failure = assertThrows(ProfileTypeConflictException.class,
                () -> controller().onboardDoctor(request("Synthetic", "012345")));

        try (var response = new ProfileConflictExceptionMapper().toResponse(failure)) {
            assertEquals(409, response.getStatus());
        }
        assertEquals(0, users.saves);
        assertEquals(0, doctors.saves);
    }

    @Test
    void unexpectedServiceFailuresAreNotConvertedToBadRequests() {
        var failure = new IllegalStateException("Internal failure must not become a validation response");
        users.failure = failure;

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> controller().onboardDoctor(request("Synthetic", "012345"))));
        assertEquals(0, users.saves);
        assertEquals(0, doctors.saves);
    }

    private void seed() {
        onboarding.handle(new OnboardDoctorCommand(
                "doctor-sub", "Synthetic", "Doctor", null, null, null, null, "012345"));
        users.saves = 0;
        doctors.saves = 0;
    }

    private static OnboardDoctorRequest request(String firstName, String cmp) {
        return new OnboardDoctorRequest(firstName, "Doctor", null, null, null, null, cmp);
    }

    private ProfileController controller() {
        var jwt = (JsonWebToken) Proxy.newProxyInstance(JsonWebToken.class.getClassLoader(),
                new Class<?>[] {JsonWebToken.class}, (proxy, method, args) ->
                        method.getName().equals("getSubject") ? "doctor-sub" : null);
        var identity = (SecurityIdentity) Proxy.newProxyInstance(SecurityIdentity.class.getClassLoader(),
                new Class<?>[] {SecurityIdentity.class}, (proxy, method, args) ->
                        method.getName().equals("getPrincipal") ? jwt : null);
        var services = new TransactionalCurrentProfileService(
                new GetCurrentProfileQueryService(users, doctors),
                new CompleteCurrentProfileCommandService(users, CLOCK), onboarding);
        return new ProfileController(services, identity);
    }

    private static final class Users implements UserProfileRepository {
        private UserProfile profile;
        private RuntimeException failure;
        private int saves;
        @Override public Optional<UserProfile> findByCognitoSubject(CognitoSubject subject) {
            if (failure != null) throw failure;
            return Optional.ofNullable(profile).filter(value -> value.cognitoSubject().equals(subject));
        }
        @Override public void save(UserProfile value) { profile = value; saves++; }
    }

    private static final class Doctors implements DoctorProfileRepository {
        private DoctorProfile profile;
        private int saves;
        @Override public Optional<DoctorProfile> findByProfileId(ProfileId id) {
            return Optional.ofNullable(profile).filter(value -> value.profileId().equals(id));
        }
        @Override public void save(DoctorProfile value) { profile = value; saves++; }
    }
}

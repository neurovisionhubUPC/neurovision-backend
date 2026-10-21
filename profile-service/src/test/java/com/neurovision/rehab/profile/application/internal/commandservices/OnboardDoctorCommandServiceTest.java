package com.neurovision.rehab.profile.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.profile.application.ports.out.persistence.DoctorProfileRepository;
import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import com.neurovision.rehab.profile.domain.model.aggregates.DoctorProfile;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.DoctorProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OnboardDoctorCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T21:04:30Z");
    private static final UUID USER_PROFILE_ID =
            UUID.fromString("1f665873-4b23-4720-9b82-f13f334702ff");
    private static final UUID DOCTOR_PROFILE_ID =
            UUID.fromString("157816c1-4640-42df-aa63-68c1c29dbf80");

    @Test
    void shouldCompleteNamesOnlyWithoutProfessionalEligibility() {
        var users = new InMemoryUserProfileRepository();
        var doctors = new InMemoryDoctorProfileRepository();
        var result = service(users, doctors).handle(new OnboardDoctorCommand(
                "doctor-sub", "Marco", "Bill", null, null, null, null, null));
        assertEquals(ProfileStatus.ACTIVE, result.userProfile().status());
        org.junit.jupiter.api.Assertions.assertNull(result.doctorProfile().professionalDetails().medicalLicense());
        org.junit.jupiter.api.Assertions.assertNull(result.userProfile().personalDetails().orElseThrow().phone());
        var again = service(users, doctors).handle(new OnboardDoctorCommand(
                "doctor-sub", "Marco", "Bill", null, null, null, null, null));
        assertEquals(result.doctorProfile().id(), again.doctorProfile().id());
    }

    @Test
    void invalidCmpDoesNotMutateAnExistingProfileOrSaveChanges() {
        var users = new InMemoryUserProfileRepository();
        var doctors = new InMemoryDoctorProfileRepository();
        var service = service(users, doctors);
        service.handle(command("doctor-sub"));
        var before = users.profile.personalDetails();
        assertThrows(IllegalArgumentException.class, () -> service.handle(new OnboardDoctorCommand(
                "doctor-sub", "Changed", "Name", null, null, null, null, "CMP-123456")));
        assertEquals(before, users.profile.personalDetails());
        assertEquals("123456", doctors.profile.professionalDetails().medicalLicense());
    }

    @Test
    void shouldCreateAndCompleteTheDoctorProfileForANewSubject() {
        var users = new InMemoryUserProfileRepository();
        var doctors = new InMemoryDoctorProfileRepository();
        var service = service(users, doctors);

        var result = service.handle(command("doctor-sub"));

        assertEquals(ProfileStatus.ACTIVE, result.userProfile().status());
        assertEquals("Neurology", result.doctorProfile().professionalDetails().specialty());
        assertEquals(result.userProfile().id(), result.doctorProfile().profileId());
        assertEquals(result.userProfile(), users.profile);
        assertEquals(result.doctorProfile(), doctors.profile);
    }

    @Test
    void shouldUpdateTheExistingDoctorOnboardingIdempotently() {
        var users = new InMemoryUserProfileRepository();
        var doctors = new InMemoryDoctorProfileRepository();
        var service = service(users, doctors);
        var first = service.handle(command("doctor-sub"));

        var second = service.handle(command("doctor-sub"));

        assertEquals(first.userProfile().id(), second.userProfile().id());
        assertEquals(first.doctorProfile().id(), second.doctorProfile().id());
        assertEquals(first.userProfile().updatedAt(), second.userProfile().updatedAt());
        assertEquals(first.doctorProfile().updatedAt(), second.doctorProfile().updatedAt());
    }

    @Test
    void shouldRejectAPatientSubjectUsingTheDoctorOnboardingContract() {
        var users = new InMemoryUserProfileRepository();
        users.profile = UserProfile.registerInvitedPatient(
                new ProfileId(USER_PROFILE_ID), new CognitoSubject("patient-sub"), NOW);
        var service = service(users, new InMemoryDoctorProfileRepository());

        assertThrows(ProfileTypeConflictException.class, () -> service.handle(command("patient-sub")));
    }

    private static OnboardDoctorCommandService service(
            UserProfileRepository users, DoctorProfileRepository doctors) {
        return new OnboardDoctorCommandService(
                users,
                doctors,
                () -> USER_PROFILE_ID,
                () -> DOCTOR_PROFILE_ID,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OnboardDoctorCommand command(String subject) {
        return new OnboardDoctorCommand(
                subject,
                "Marco",
                "Bill",
                "44556677",
                LocalDate.of(1985, 6, 15),
                "+51 987 654 321",
                "Neurology",
                "123456");
    }

    private static final class InMemoryUserProfileRepository implements UserProfileRepository {
        private UserProfile profile;

        @Override
        public Optional<UserProfile> findByCognitoSubject(CognitoSubject subject) {
            return Optional.ofNullable(profile)
                    .filter(current -> current.cognitoSubject().equals(subject));
        }

        @Override
        public void save(UserProfile profile) {
            this.profile = profile;
        }
    }

    private static final class InMemoryDoctorProfileRepository implements DoctorProfileRepository {
        private DoctorProfile profile;

        @Override
        public Optional<DoctorProfile> findByProfileId(ProfileId profileId) {
            return Optional.ofNullable(profile)
                    .filter(current -> current.profileId().equals(profileId));
        }

        @Override
        public void save(DoctorProfile profile) {
            this.profile = profile;
        }
    }
}

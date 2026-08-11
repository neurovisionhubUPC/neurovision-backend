package com.neurovision.rehab.profile.domain.model.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.PersonalDetails;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileStatus;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserProfileTest {

    private static final ProfileId PROFILE_ID =
            new ProfileId(UUID.fromString("1f665873-4b23-4720-9b82-f13f334702ff"));
    private static final CognitoSubject PATIENT_SUBJECT =
            new CognitoSubject("44b884b8-d0c1-70f2-c311-c56bb74764a9");
    private static final Instant CREATED_AT = Instant.parse("2026-08-10T21:04:30Z");

    @Test
    void shouldRegisterAnInvitedPatientPendingProfileCompletion() {
        var profile = UserProfile.registerInvitedPatient(PROFILE_ID, PATIENT_SUBJECT, CREATED_AT);

        assertEquals(PROFILE_ID, profile.id());
        assertEquals(PATIENT_SUBJECT, profile.cognitoSubject());
        assertEquals(ProfileType.PATIENT, profile.type());
        assertEquals(ProfileStatus.PENDING_COMPLETION, profile.status());
        assertEquals(CREATED_AT, profile.createdAt());
        assertEquals(CREATED_AT, profile.updatedAt());
    }

    @Test
    void shouldRegisterADoctorPendingProfileCompletion() {
        var subject = new CognitoSubject("doctor-cognito-sub");

        var profile = UserProfile.registerDoctor(PROFILE_ID, subject, CREATED_AT);

        assertEquals(subject, profile.cognitoSubject());
        assertEquals(ProfileType.DOCTOR, profile.type());
        assertEquals(ProfileStatus.PENDING_COMPLETION, profile.status());
    }

    @Test
    void shouldRehydrateAnExistingActiveDoctorProfile() {
        var updatedAt = CREATED_AT.plusSeconds(60);

        var profile = UserProfile.rehydrate(
                PROFILE_ID,
                new CognitoSubject("7458e448-6051-7081-2745-186f682d626c"),
                ProfileType.DOCTOR,
                ProfileStatus.ACTIVE,
                personalDetails(),
                CREATED_AT,
                updatedAt);

        assertEquals(ProfileType.DOCTOR, profile.type());
        assertEquals(ProfileStatus.ACTIVE, profile.status());
        assertEquals(updatedAt, profile.updatedAt());
    }

    @Test
    void shouldRejectAnUpdateTimeBeforeCreation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> UserProfile.rehydrate(
                        PROFILE_ID,
                        PATIENT_SUBJECT,
                        ProfileType.PATIENT,
                        ProfileStatus.PENDING_COMPLETION,
                        null,
                        CREATED_AT,
                        CREATED_AT.minusSeconds(1)));
    }

    @Test
    void shouldRejectMissingRegistrationData() {
        assertThrows(
                NullPointerException.class,
                () -> UserProfile.registerInvitedPatient(null, PATIENT_SUBJECT, CREATED_AT));
        assertThrows(
                NullPointerException.class,
                () -> UserProfile.registerInvitedPatient(PROFILE_ID, null, CREATED_AT));
        assertThrows(
                NullPointerException.class,
                () -> UserProfile.registerInvitedPatient(PROFILE_ID, PATIENT_SUBJECT, null));
    }

    @Test
    void shouldCompleteAnInvitedPatientProfile() {
        var profile = UserProfile.registerInvitedPatient(PROFILE_ID, PATIENT_SUBJECT, CREATED_AT);
        var details = personalDetails();
        var completedAt = CREATED_AT.plusSeconds(60);

        profile.complete(details, completedAt);

        assertEquals(ProfileStatus.ACTIVE, profile.status());
        assertEquals(details, profile.personalDetails().orElseThrow());
        assertEquals(completedAt, profile.updatedAt());
    }

    @Test
    void shouldKeepAnIdenticalCompletionIdempotent() {
        var profile = UserProfile.registerInvitedPatient(PROFILE_ID, PATIENT_SUBJECT, CREATED_AT);
        var details = personalDetails();
        var completedAt = CREATED_AT.plusSeconds(60);
        profile.complete(details, completedAt);

        profile.complete(details, completedAt.plusSeconds(60));

        assertEquals(completedAt, profile.updatedAt());
    }

    @Test
    void shouldRejectCompletionBeforeTheLastUpdate() {
        var profile = UserProfile.registerInvitedPatient(PROFILE_ID, PATIENT_SUBJECT, CREATED_AT);

        assertThrows(
                IllegalArgumentException.class,
                () -> profile.complete(personalDetails(), CREATED_AT.minusSeconds(1)));
    }

    private static PersonalDetails personalDetails() {
        return new PersonalDetails(
                "Andrea",
                "Salazar",
                "74859621",
                LocalDate.of(1998, 4, 12),
                "+51 987 654 321");
    }
}

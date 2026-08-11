package com.neurovision.rehab.profile.domain.model.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.profile.domain.model.valueobjects.DoctorProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfessionalDetails;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DoctorProfileTest {

    private static final DoctorProfileId DOCTOR_PROFILE_ID =
            new DoctorProfileId(UUID.fromString("157816c1-4640-42df-aa63-68c1c29dbf80"));
    private static final ProfileId PROFILE_ID =
            new ProfileId(UUID.fromString("1f665873-4b23-4720-9b82-f13f334702ff"));
    private static final Instant CREATED_AT = Instant.parse("2026-08-10T21:04:30Z");

    @Test
    void shouldCreateAProfessionalExtensionForADoctor() {
        var profile = DoctorProfile.create(
                DOCTOR_PROFILE_ID, PROFILE_ID, details(), CREATED_AT);

        assertEquals(PROFILE_ID, profile.profileId());
        assertEquals("Neurology", profile.professionalDetails().specialty());
        assertEquals("CMP-123456", profile.professionalDetails().medicalLicense());
        assertEquals(CREATED_AT, profile.updatedAt());
    }

    @Test
    void shouldKeepAnIdenticalUpdateIdempotent() {
        var profile = DoctorProfile.create(
                DOCTOR_PROFILE_ID, PROFILE_ID, details(), CREATED_AT);

        profile.update(details(), CREATED_AT.plusSeconds(60));

        assertEquals(CREATED_AT, profile.updatedAt());
    }

    @Test
    void shouldRejectInvalidProfessionalDetails() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProfessionalDetails(" ", "CMP-123456"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProfessionalDetails("Neurology", " "));
    }

    @Test
    void shouldNormalizeMedicalLicensesForConsistentUniqueness() {
        var details = new ProfessionalDetails("Neurology", "cmp-123456");

        assertEquals("CMP-123456", details.medicalLicense());
    }

    private static ProfessionalDetails details() {
        return new ProfessionalDetails("Neurology", "CMP-123456");
    }
}

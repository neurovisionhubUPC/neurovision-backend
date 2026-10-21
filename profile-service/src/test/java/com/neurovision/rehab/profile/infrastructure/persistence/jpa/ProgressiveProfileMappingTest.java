package com.neurovision.rehab.profile.infrastructure.persistence.jpa;

import com.neurovision.rehab.profile.domain.model.aggregates.*;
import com.neurovision.rehab.profile.domain.model.valueobjects.*;
import com.neurovision.rehab.profile.infrastructure.persistence.jpa.entities.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ProgressiveProfileMappingTest {
    @Test void nullablePersonalAndProfessionalFieldsRoundTripWithoutLosingNames() {
        var user = UserProfile.registerDoctor(new ProfileId(UUID.randomUUID()), new CognitoSubject("synthetic-doctor"), Instant.EPOCH);
        user.complete(new PersonalDetails("Ana", "Test", null, null, null), Instant.EPOCH);
        var restored = UserProfileEntity.fromDomain(user).toDomain();
        assertEquals(user.personalDetails(), restored.personalDetails());
        var doctor = DoctorProfile.create(new DoctorProfileId(UUID.randomUUID()), user.id(), new ProfessionalDetails(null, null), Instant.EPOCH);
        assertEquals(doctor.professionalDetails(), DoctorProfileEntity.fromDomain(doctor).toDomain().professionalDetails());
        var legacy = DoctorProfile.rehydrate(doctor.id(), user.id(), ProfessionalDetails.fromStored("Neurology", "CMP-123456"), Instant.EPOCH, Instant.EPOCH);
        assertEquals("CMP-123456", DoctorProfileEntity.fromDomain(legacy).toDomain().professionalDetails().medicalLicense());
    }
    @Test void forwardMigrationPreservesRowsUniquenessAndPendingState() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/V4__allow_progressive_profile_completion.sql")) {
            assertNotNull(stream);
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("status = 'PENDING_COMPLETION'"));
            assertTrue(sql.contains("first_name IS NULL AND last_name IS NULL"));
            assertTrue(sql.contains("status = 'ACTIVE' AND first_name IS NOT NULL AND last_name IS NOT NULL"));
            assertTrue(sql.contains("ALTER COLUMN specialty DROP NOT NULL"));
            assertTrue(sql.contains("ALTER COLUMN medical_license DROP NOT NULL"));
            assertFalse(sql.matches("(?is).*\\b(DELETE|UPDATE|TRUNCATE|INSERT)\\b.*"));
            assertFalse(sql.contains("DROP CONSTRAINT uq_"));
        }
    }
}

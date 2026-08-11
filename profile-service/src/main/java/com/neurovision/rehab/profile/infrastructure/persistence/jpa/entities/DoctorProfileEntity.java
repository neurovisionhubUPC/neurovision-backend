package com.neurovision.rehab.profile.infrastructure.persistence.jpa.entities;

import com.neurovision.rehab.profile.domain.model.aggregates.DoctorProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.DoctorProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfessionalDetails;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "doctor_profiles")
public class DoctorProfileEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "profile_id", nullable = false, unique = true, updatable = false)
    private UUID profileId;

    @Column(name = "specialty", nullable = false, length = 100)
    private String specialty;

    @Column(name = "medical_license", nullable = false, unique = true, length = 64)
    private String medicalLicense;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected DoctorProfileEntity() {}

    public static DoctorProfileEntity fromDomain(DoctorProfile profile) {
        var entity = new DoctorProfileEntity();
        entity.id = profile.id().value();
        entity.profileId = profile.profileId().value();
        entity.createdAt = profile.createdAt();
        entity.updateFrom(profile);
        return entity;
    }

    public void updateFrom(DoctorProfile profile) {
        if (!id.equals(profile.id().value())) {
            throw new IllegalArgumentException("Cannot update a different doctor profile");
        }
        if (!profileId.equals(profile.profileId().value())) {
            throw new IllegalArgumentException("Cannot change the parent user profile");
        }
        specialty = profile.professionalDetails().specialty();
        medicalLicense = profile.professionalDetails().medicalLicense();
        updatedAt = profile.updatedAt();
    }

    public DoctorProfile toDomain() {
        return DoctorProfile.rehydrate(
                new DoctorProfileId(id),
                new ProfileId(profileId),
                new ProfessionalDetails(specialty, medicalLicense),
                createdAt,
                updatedAt);
    }
}

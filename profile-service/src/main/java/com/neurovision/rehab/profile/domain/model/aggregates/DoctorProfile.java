package com.neurovision.rehab.profile.domain.model.aggregates;

import com.neurovision.rehab.profile.domain.model.valueobjects.DoctorProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfessionalDetails;
import java.time.Instant;
import java.util.Objects;

public final class DoctorProfile {

    private final DoctorProfileId id;
    private final ProfileId profileId;
    private ProfessionalDetails professionalDetails;
    private final Instant createdAt;
    private Instant updatedAt;

    private DoctorProfile(
            DoctorProfileId id,
            ProfileId profileId,
            ProfessionalDetails professionalDetails,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "Doctor profile ID must not be null");
        this.profileId = Objects.requireNonNull(profileId, "Profile ID must not be null");
        this.professionalDetails =
                Objects.requireNonNull(professionalDetails, "Professional details must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Update time must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Update time must not precede creation time");
        }
    }

    public static DoctorProfile create(
            DoctorProfileId id,
            ProfileId profileId,
            ProfessionalDetails professionalDetails,
            Instant createdAt) {
        return new DoctorProfile(id, profileId, professionalDetails, createdAt, createdAt);
    }

    public static DoctorProfile rehydrate(
            DoctorProfileId id,
            ProfileId profileId,
            ProfessionalDetails professionalDetails,
            Instant createdAt,
            Instant updatedAt) {
        return new DoctorProfile(id, profileId, professionalDetails, createdAt, updatedAt);
    }

    public void update(ProfessionalDetails details, Instant changedAt) {
        Objects.requireNonNull(details, "Professional details must not be null");
        Objects.requireNonNull(changedAt, "Update time must not be null");
        if (changedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Update time must not precede the last update");
        }
        if (professionalDetails.equals(details)) {
            return;
        }
        professionalDetails = details;
        updatedAt = changedAt;
    }

    public DoctorProfileId id() {
        return id;
    }

    public ProfileId profileId() {
        return profileId;
    }

    public ProfessionalDetails professionalDetails() {
        return professionalDetails;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

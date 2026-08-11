package com.neurovision.rehab.profile.domain.model.aggregates;

import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.PersonalDetails;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileStatus;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileType;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class UserProfile {

    private final ProfileId id;
    private final CognitoSubject cognitoSubject;
    private final ProfileType type;
    private ProfileStatus status;
    private PersonalDetails personalDetails;
    private final Instant createdAt;
    private Instant updatedAt;

    private UserProfile(
            ProfileId id,
            CognitoSubject cognitoSubject,
            ProfileType type,
            ProfileStatus status,
            PersonalDetails personalDetails,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "Profile ID must not be null");
        this.cognitoSubject =
                Objects.requireNonNull(cognitoSubject, "Cognito subject must not be null");
        this.type = Objects.requireNonNull(type, "Profile type must not be null");
        this.status = Objects.requireNonNull(status, "Profile status must not be null");
        this.personalDetails = personalDetails;
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Update time must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Update time must not precede creation time");
        }
        validateState(status, personalDetails);
    }

    public static UserProfile registerInvitedPatient(
            ProfileId id, CognitoSubject cognitoSubject, Instant occurredAt) {
        return new UserProfile(
                id,
                cognitoSubject,
                ProfileType.PATIENT,
                ProfileStatus.PENDING_COMPLETION,
                null,
                occurredAt,
                occurredAt);
    }

    public static UserProfile rehydrate(
            ProfileId id,
            CognitoSubject cognitoSubject,
            ProfileType type,
            ProfileStatus status,
            PersonalDetails personalDetails,
            Instant createdAt,
            Instant updatedAt) {
        return new UserProfile(
                id, cognitoSubject, type, status, personalDetails, createdAt, updatedAt);
    }

    public void complete(PersonalDetails personalDetails, Instant completedAt) {
        Objects.requireNonNull(personalDetails, "Personal details must not be null");
        Objects.requireNonNull(completedAt, "Completion time must not be null");
        if (completedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Completion time must not precede the last update");
        }
        if (status == ProfileStatus.ACTIVE && personalDetails.equals(this.personalDetails)) {
            return;
        }
        this.personalDetails = personalDetails;
        this.status = ProfileStatus.ACTIVE;
        this.updatedAt = completedAt;
    }

    private static void validateState(ProfileStatus status, PersonalDetails personalDetails) {
        if (status == ProfileStatus.PENDING_COMPLETION && personalDetails != null) {
            throw new IllegalArgumentException("Pending profile must not contain personal details");
        }
        if (status == ProfileStatus.ACTIVE && personalDetails == null) {
            throw new IllegalArgumentException("Active profile requires personal details");
        }
    }

    public ProfileId id() {
        return id;
    }

    public CognitoSubject cognitoSubject() {
        return cognitoSubject;
    }

    public ProfileType type() {
        return type;
    }

    public ProfileStatus status() {
        return status;
    }

    public Optional<PersonalDetails> personalDetails() {
        return Optional.ofNullable(personalDetails);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

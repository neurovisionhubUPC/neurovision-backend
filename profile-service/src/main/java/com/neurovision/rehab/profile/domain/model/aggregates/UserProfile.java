package com.neurovision.rehab.profile.domain.model.aggregates;

import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileStatus;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileType;
import java.time.Instant;
import java.util.Objects;

public final class UserProfile {

    private final ProfileId id;
    private final CognitoSubject cognitoSubject;
    private final ProfileType type;
    private final ProfileStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private UserProfile(
            ProfileId id,
            CognitoSubject cognitoSubject,
            ProfileType type,
            ProfileStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "Profile ID must not be null");
        this.cognitoSubject =
                Objects.requireNonNull(cognitoSubject, "Cognito subject must not be null");
        this.type = Objects.requireNonNull(type, "Profile type must not be null");
        this.status = Objects.requireNonNull(status, "Profile status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Update time must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Update time must not precede creation time");
        }
    }

    public static UserProfile registerInvitedPatient(
            ProfileId id, CognitoSubject cognitoSubject, Instant occurredAt) {
        return new UserProfile(
                id,
                cognitoSubject,
                ProfileType.PATIENT,
                ProfileStatus.PENDING_COMPLETION,
                occurredAt,
                occurredAt);
    }

    public static UserProfile rehydrate(
            ProfileId id,
            CognitoSubject cognitoSubject,
            ProfileType type,
            ProfileStatus status,
            Instant createdAt,
            Instant updatedAt) {
        return new UserProfile(id, cognitoSubject, type, status, createdAt, updatedAt);
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

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

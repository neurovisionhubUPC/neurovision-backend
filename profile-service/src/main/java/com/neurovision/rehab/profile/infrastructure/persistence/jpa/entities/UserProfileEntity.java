package com.neurovision.rehab.profile.infrastructure.persistence.jpa.entities;

import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileStatus;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_profiles")
public class UserProfileEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "cognito_sub", nullable = false, unique = true, length = 128, updatable = false)
    private String cognitoSubject;

    @Column(name = "profile_type", nullable = false, length = 16, updatable = false)
    private String profileType;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected UserProfileEntity() {}

    public static UserProfileEntity fromDomain(UserProfile profile) {
        var entity = new UserProfileEntity();
        entity.id = profile.id().value();
        entity.cognitoSubject = profile.cognitoSubject().value();
        entity.profileType = profile.type().name();
        entity.createdAt = profile.createdAt();
        entity.updateFrom(profile);
        return entity;
    }

    public void updateFrom(UserProfile profile) {
        if (!id.equals(profile.id().value())) {
            throw new IllegalArgumentException("Cannot update a different user profile");
        }
        if (!cognitoSubject.equals(profile.cognitoSubject().value())) {
            throw new IllegalArgumentException("Cannot change the Cognito subject of a profile");
        }
        if (!profileType.equals(profile.type().name())) {
            throw new IllegalArgumentException("Cannot change the profile type");
        }
        status = profile.status().name();
        updatedAt = profile.updatedAt();
    }

    public UserProfile toDomain() {
        return UserProfile.rehydrate(
                new ProfileId(id),
                new CognitoSubject(cognitoSubject),
                ProfileType.valueOf(profileType),
                ProfileStatus.valueOf(status),
                createdAt,
                updatedAt);
    }
}

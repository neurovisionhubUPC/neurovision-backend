package com.neurovision.rehab.profile.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.infrastructure.persistence.jpa.entities.UserProfileEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class JpaUserProfileRepository implements UserProfileRepository {

    private final UserProfilePanacheRepository entities;

    public JpaUserProfileRepository(UserProfilePanacheRepository entities) {
        this.entities = Objects.requireNonNull(entities);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<UserProfile> findByCognitoSubject(CognitoSubject cognitoSubject) {
        Objects.requireNonNull(cognitoSubject, "Cognito subject must not be null");
        return Optional.ofNullable(entities.findByCognitoSubject(cognitoSubject.value()))
                .map(UserProfileEntity::toDomain);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public void save(UserProfile profile) {
        Objects.requireNonNull(profile, "User profile must not be null");
        entities.findByIdOptional(profile.id().value()).ifPresentOrElse(
                entity -> entity.updateFrom(profile),
                () -> entities.persist(UserProfileEntity.fromDomain(profile)));
        entities.flush();
    }
}

package com.neurovision.rehab.profile.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.profile.infrastructure.persistence.jpa.entities.UserProfileEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class UserProfilePanacheRepository
        implements PanacheRepositoryBase<UserProfileEntity, UUID> {

    public UserProfileEntity findByCognitoSubject(String cognitoSubject) {
        return find("cognitoSubject", cognitoSubject).firstResult();
    }
}

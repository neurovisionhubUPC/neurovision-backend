package com.neurovision.rehab.iam.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.iam.infrastructure.persistence.jpa.entities.OutboxEventEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class OutboxEventPanacheRepository implements PanacheRepositoryBase<OutboxEventEntity, UUID> {}

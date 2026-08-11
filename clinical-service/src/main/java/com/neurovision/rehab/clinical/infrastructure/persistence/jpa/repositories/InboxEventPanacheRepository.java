package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.InboxEventEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class InboxEventPanacheRepository implements PanacheRepositoryBase<InboxEventEntity, UUID> {}

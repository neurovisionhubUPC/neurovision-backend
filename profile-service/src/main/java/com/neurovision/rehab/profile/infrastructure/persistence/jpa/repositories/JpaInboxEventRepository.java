package com.neurovision.rehab.profile.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.profile.application.ports.out.persistence.InboxEventRepository;
import com.neurovision.rehab.profile.application.ports.out.persistence.ProcessedInboxEvent;
import com.neurovision.rehab.profile.infrastructure.persistence.jpa.entities.InboxEventEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class JpaInboxEventRepository implements InboxEventRepository {

    private final InboxEventPanacheRepository entities;

    public JpaInboxEventRepository(InboxEventPanacheRepository entities) {
        this.entities = Objects.requireNonNull(entities);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public boolean existsByEventId(UUID eventId) {
        Objects.requireNonNull(eventId, "Event ID must not be null");
        return entities.findByIdOptional(eventId).isPresent();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public void save(ProcessedInboxEvent event) {
        Objects.requireNonNull(event, "Processed inbox event must not be null");
        entities.persist(InboxEventEntity.from(event));
        entities.flush();
    }
}

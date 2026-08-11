package com.neurovision.rehab.clinical.application.ports.out.persistence;

import java.util.UUID;

public interface InboxEventRepository {

    boolean existsByEventId(UUID eventId);

    void save(ProcessedInboxEvent event);
}

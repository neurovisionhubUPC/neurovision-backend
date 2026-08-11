package com.neurovision.rehab.profile.application.ports.out.persistence;

import java.util.UUID;

public interface InboxEventRepository {

    boolean existsByEventId(UUID eventId);

    void save(ProcessedInboxEvent event);
}

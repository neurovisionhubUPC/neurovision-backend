package com.neurovision.rehab.profile.infrastructure.persistence.jpa.entities;

import com.neurovision.rehab.profile.application.ports.out.persistence.ProcessedInboxEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inbox_events")
public class InboxEventEntity {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 128, updatable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false, updatable = false)
    private short eventVersion;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected InboxEventEntity() {}

    public static InboxEventEntity from(ProcessedInboxEvent event) {
        var entity = new InboxEventEntity();
        entity.eventId = event.eventId();
        entity.eventType = event.eventType();
        entity.eventVersion = Short.parseShort(event.eventVersion());
        entity.occurredAt = event.occurredAt();
        entity.processedAt = event.processedAt();
        return entity;
    }
}

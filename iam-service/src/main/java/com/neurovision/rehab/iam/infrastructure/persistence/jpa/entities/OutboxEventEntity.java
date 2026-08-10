package com.neurovision.rehab.iam.infrastructure.persistence.jpa.entities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.iam.application.ports.out.messaging.PatientAccountInvitedV1;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 128, updatable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false, updatable = false)
    private short eventVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private JsonNode payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEventEntity() {}

    public static OutboxEventEntity from(PatientAccountInvitedV1 event, ObjectMapper objectMapper) {
        var entity = new OutboxEventEntity();
        entity.id = event.eventId();
        entity.aggregateType = "PatientInvitation";
        entity.aggregateId = event.invitationId();
        entity.eventType = "PatientAccountInvited";
        entity.eventVersion = 1;
        entity.occurredAt = event.occurredAt();
        entity.payload = objectMapper.createObjectNode()
                .put("eventId", event.eventId().toString())
                .put("invitationId", event.invitationId().toString())
                .put("invitedByDoctor", event.invitedByDoctor())
                .put("patientSubject", event.patientSubject())
                .put("occurredAt", event.occurredAt().toString());
        return entity;
    }

    public UUID id() {
        return id;
    }

    public JsonNode payload() {
        return payload;
    }
}

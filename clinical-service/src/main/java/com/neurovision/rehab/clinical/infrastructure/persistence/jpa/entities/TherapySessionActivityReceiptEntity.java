package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "therapy_session_activity_receipts")
public class TherapySessionActivityReceiptEntity {
    @Id @Column(nullable = false, length = 73) public String id;
    @Column(name = "therapy_session_id", nullable = false) public UUID sessionId;
    @Column(name = "event_sequence", nullable = false) public long sequence;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "receipt", nullable = false, columnDefinition = "jsonb") public String receipt;
    public TherapySessionActivityReceiptEntity() { }
}

package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "therapy_session_progress")
public class TherapySessionProgressEntity {
    @Id @Column(name = "therapy_session_id", nullable = false) public UUID sessionId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "state", nullable = false, columnDefinition = "jsonb") public String state;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "completion", columnDefinition = "jsonb") public String completion;
    public TherapySessionProgressEntity() { }
}

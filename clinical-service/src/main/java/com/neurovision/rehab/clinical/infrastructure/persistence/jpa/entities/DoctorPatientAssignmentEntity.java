package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities;

import com.neurovision.rehab.clinical.domain.model.aggregates.DoctorPatientAssignment;
import com.neurovision.rehab.clinical.domain.model.valueobjects.AssignmentStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.DoctorPatientAssignmentId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "doctor_patient_assignments")
public class DoctorPatientAssignmentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "clinical_patient_id", nullable = false, updatable = false)
    private UUID clinicalPatientId;

    @Column(name = "doctor_cognito_sub", nullable = false, length = 128, updatable = false)
    private String doctorSubject;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected DoctorPatientAssignmentEntity() {}

    public static DoctorPatientAssignmentEntity fromDomain(DoctorPatientAssignment assignment) {
        var entity = new DoctorPatientAssignmentEntity();
        entity.id = assignment.id().value();
        entity.clinicalPatientId = assignment.clinicalPatientId().value();
        entity.doctorSubject = assignment.doctorSubject().value();
        entity.assignedAt = assignment.assignedAt();
        entity.updateFrom(assignment);
        return entity;
    }

    public void updateFrom(DoctorPatientAssignment assignment) {
        if (!id.equals(assignment.id().value())) {
            throw new IllegalArgumentException("Cannot update a different assignment");
        }
        if (!clinicalPatientId.equals(assignment.clinicalPatientId().value())) {
            throw new IllegalArgumentException("Cannot change the clinical patient of an assignment");
        }
        if (!doctorSubject.equals(assignment.doctorSubject().value())) {
            throw new IllegalArgumentException("Cannot change the doctor of an assignment");
        }
        status = assignment.status().name();
        endedAt = assignment.endedAt().orElse(null);
    }

    public DoctorPatientAssignment toDomain() {
        return DoctorPatientAssignment.rehydrate(
                new DoctorPatientAssignmentId(id),
                new ClinicalPatientId(clinicalPatientId),
                new CognitoSubject(doctorSubject),
                AssignmentStatus.valueOf(status),
                assignedAt,
                endedAt);
    }
}

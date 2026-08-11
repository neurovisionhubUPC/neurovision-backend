package com.neurovision.rehab.clinical.domain.model.aggregates;

import com.neurovision.rehab.clinical.domain.model.valueobjects.AssignmentStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.DoctorPatientAssignmentId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class DoctorPatientAssignment {

    private final DoctorPatientAssignmentId id;
    private final ClinicalPatientId clinicalPatientId;
    private final CognitoSubject doctorSubject;
    private AssignmentStatus status;
    private final Instant assignedAt;
    private Instant endedAt;

    private DoctorPatientAssignment(
            DoctorPatientAssignmentId id,
            ClinicalPatientId clinicalPatientId,
            CognitoSubject doctorSubject,
            AssignmentStatus status,
            Instant assignedAt,
            Instant endedAt) {
        this.id = Objects.requireNonNull(id, "Assignment ID must not be null");
        this.clinicalPatientId =
                Objects.requireNonNull(clinicalPatientId, "Clinical patient ID must not be null");
        this.doctorSubject =
                Objects.requireNonNull(doctorSubject, "Doctor subject must not be null");
        this.status = Objects.requireNonNull(status, "Assignment status must not be null");
        this.assignedAt = Objects.requireNonNull(assignedAt, "Assignment time must not be null");
        this.endedAt = endedAt;
        validateState(status, assignedAt, endedAt);
    }

    public static DoctorPatientAssignment assign(
            DoctorPatientAssignmentId id,
            ClinicalPatientId clinicalPatientId,
            CognitoSubject doctorSubject,
            Instant assignedAt) {
        return new DoctorPatientAssignment(
                id,
                clinicalPatientId,
                doctorSubject,
                AssignmentStatus.ACTIVE,
                assignedAt,
                null);
    }

    public static DoctorPatientAssignment rehydrate(
            DoctorPatientAssignmentId id,
            ClinicalPatientId clinicalPatientId,
            CognitoSubject doctorSubject,
            AssignmentStatus status,
            Instant assignedAt,
            Instant endedAt) {
        return new DoctorPatientAssignment(
                id, clinicalPatientId, doctorSubject, status, assignedAt, endedAt);
    }

    public void end(Instant endedAt) {
        Objects.requireNonNull(endedAt, "Assignment end time must not be null");
        if (endedAt.isBefore(assignedAt)) {
            throw new IllegalArgumentException("Assignment end time must not precede assignment time");
        }
        if (status == AssignmentStatus.ENDED) {
            return;
        }
        status = AssignmentStatus.ENDED;
        this.endedAt = endedAt;
    }

    private static void validateState(
            AssignmentStatus status, Instant assignedAt, Instant endedAt) {
        if (status == AssignmentStatus.ACTIVE && endedAt != null) {
            throw new IllegalArgumentException("Active assignment must not have an end time");
        }
        if (status == AssignmentStatus.ENDED && endedAt == null) {
            throw new IllegalArgumentException("Ended assignment requires an end time");
        }
        if (endedAt != null && endedAt.isBefore(assignedAt)) {
            throw new IllegalArgumentException("Assignment end time must not precede assignment time");
        }
    }

    public DoctorPatientAssignmentId id() {
        return id;
    }

    public ClinicalPatientId clinicalPatientId() {
        return clinicalPatientId;
    }

    public CognitoSubject doctorSubject() {
        return doctorSubject;
    }

    public AssignmentStatus status() {
        return status;
    }

    public Instant assignedAt() {
        return assignedAt;
    }

    public Optional<Instant> endedAt() {
        return Optional.ofNullable(endedAt);
    }
}

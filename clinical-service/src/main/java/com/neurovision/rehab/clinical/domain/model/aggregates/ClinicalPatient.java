package com.neurovision.rehab.clinical.domain.model.aggregates;

import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.Sequela;
import com.neurovision.rehab.clinical.domain.model.valueobjects.StrokeDiagnosis;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ClinicalPatient {

    private final ClinicalPatientId id;
    private final CognitoSubject patientSubject;
    private ClinicalPatientStatus status;
    private StrokeDiagnosis strokeDiagnosis;
    private Set<Sequela> sequelae;
    private final Instant createdAt;
    private Instant updatedAt;

    private ClinicalPatient(
            ClinicalPatientId id,
            CognitoSubject patientSubject,
            ClinicalPatientStatus status,
            StrokeDiagnosis strokeDiagnosis,
            Set<Sequela> sequelae,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "Clinical patient ID must not be null");
        this.patientSubject =
                Objects.requireNonNull(patientSubject, "Patient subject must not be null");
        this.status = Objects.requireNonNull(status, "Clinical patient status must not be null");
        this.strokeDiagnosis = strokeDiagnosis;
        this.sequelae = immutableSequelae(sequelae);
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Update time must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Update time must not precede creation time");
        }
        validateState(status, strokeDiagnosis, this.sequelae);
    }

    public static ClinicalPatient registerInvited(
            ClinicalPatientId id, CognitoSubject patientSubject, Instant registeredAt) {
        return new ClinicalPatient(
                id,
                patientSubject,
                ClinicalPatientStatus.ACTIVE,
                null,
                Set.of(),
                registeredAt,
                registeredAt);
    }

    public static ClinicalPatient rehydrate(
            ClinicalPatientId id,
            CognitoSubject patientSubject,
            ClinicalPatientStatus status,
            StrokeDiagnosis strokeDiagnosis,
            Set<Sequela> sequelae,
            Instant createdAt,
            Instant updatedAt) {
        return new ClinicalPatient(
                id, patientSubject, status, strokeDiagnosis, sequelae, createdAt, updatedAt);
    }

    public void recordAssessment(
            StrokeDiagnosis diagnosis, Set<Sequela> sequelae, Instant assessedAt) {
        Objects.requireNonNull(diagnosis, "Stroke diagnosis must not be null");
        var normalizedSequelae = immutableSequelae(sequelae);
        Objects.requireNonNull(assessedAt, "Assessment time must not be null");
        if (assessedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Assessment time must not precede the last update");
        }
        if (status != ClinicalPatientStatus.ACTIVE) {
            throw new IllegalStateException("Legacy pending registration requires migration before editing");
        }
        if (status == ClinicalPatientStatus.ACTIVE
                && diagnosis.equals(strokeDiagnosis)
                && normalizedSequelae.equals(this.sequelae)) {
            return;
        }

        strokeDiagnosis = diagnosis;
        this.sequelae = normalizedSequelae;
        updatedAt = assessedAt;
    }

    private static Set<Sequela> immutableSequelae(Set<Sequela> sequelae) {
        Objects.requireNonNull(sequelae, "Sequelae must not be null");
        return Set.copyOf(sequelae);
    }

    private static void validateState(
            ClinicalPatientStatus status,
            StrokeDiagnosis strokeDiagnosis,
            Set<Sequela> sequelae) {
        if (status == ClinicalPatientStatus.PENDING_ASSESSMENT
                && (strokeDiagnosis != null || !sequelae.isEmpty())) {
            throw new IllegalArgumentException(
                    "Pending clinical patient must not contain assessment data");
        }
    }

    public ClinicalPatientId id() {
        return id;
    }

    public CognitoSubject patientSubject() {
        return patientSubject;
    }

    public ClinicalPatientStatus status() {
        return status;
    }

    public Optional<StrokeDiagnosis> strokeDiagnosis() {
        return Optional.ofNullable(strokeDiagnosis);
    }

    public Set<Sequela> sequelae() {
        return sequelae;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

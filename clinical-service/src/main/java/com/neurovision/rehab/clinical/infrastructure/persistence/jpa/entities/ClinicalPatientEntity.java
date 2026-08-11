package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities;

import com.neurovision.rehab.clinical.domain.model.aggregates.ClinicalPatient;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.Sequela;
import com.neurovision.rehab.clinical.domain.model.valueobjects.StrokeDiagnosis;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "clinical_patients")
public class ClinicalPatientEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "patient_cognito_sub", nullable = false, unique = true, length = 128, updatable = false)
    private String patientSubject;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "stroke_diagnosis", length = 500)
    private String strokeDiagnosis;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "sequelae", nullable = false, columnDefinition = "text[]")
    private String[] sequelae;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ClinicalPatientEntity() {}

    public static ClinicalPatientEntity fromDomain(ClinicalPatient patient) {
        var entity = new ClinicalPatientEntity();
        entity.id = patient.id().value();
        entity.patientSubject = patient.patientSubject().value();
        entity.createdAt = patient.createdAt();
        entity.updateFrom(patient);
        return entity;
    }

    public void updateFrom(ClinicalPatient patient) {
        if (!id.equals(patient.id().value())) {
            throw new IllegalArgumentException("Cannot update a different clinical patient");
        }
        if (!patientSubject.equals(patient.patientSubject().value())) {
            throw new IllegalArgumentException("Cannot change the patient Cognito subject");
        }
        status = patient.status().name();
        strokeDiagnosis = patient.strokeDiagnosis().map(StrokeDiagnosis::value).orElse(null);
        sequelae = patient.sequelae().stream().map(Sequela::value).sorted().toArray(String[]::new);
        updatedAt = patient.updatedAt();
    }

    public ClinicalPatient toDomain() {
        return ClinicalPatient.rehydrate(
                new ClinicalPatientId(id),
                new CognitoSubject(patientSubject),
                ClinicalPatientStatus.valueOf(status),
                strokeDiagnosis == null ? null : new StrokeDiagnosis(strokeDiagnosis),
                sequelae(),
                createdAt,
                updatedAt);
    }

    private Set<Sequela> sequelae() {
        if (sequelae == null || sequelae.length == 0) {
            return Set.of();
        }
        return Arrays.stream(sequelae).map(Sequela::new).collect(Collectors.toUnmodifiableSet());
    }
}

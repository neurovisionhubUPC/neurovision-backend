package com.neurovision.rehab.iam.infrastructure.persistence.jpa.entities;

import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patient_invitations")
public class PatientInvitationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "doctor_cognito_sub", nullable = false, length = 128, updatable = false)
    private String doctorCognitoSub;

    @Column(name = "patient_cognito_sub", length = 128)
    private String patientCognitoSub;

    @Column(name = "patient_email", nullable = false, length = 254, updatable = false)
    private String patientEmail;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "failure_code", length = 128)
    private String failureCode;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PatientInvitationEntity() {}

    public static PatientInvitationEntity fromDomain(PatientInvitation invitation) {
        var entity = new PatientInvitationEntity();
        entity.id = invitation.id().value();
        entity.doctorCognitoSub = invitation.invitedByDoctor().value();
        entity.patientEmail = invitation.patientEmail().value();
        entity.createdAt = invitation.createdAt();
        entity.updateFrom(invitation);
        return entity;
    }

    public void updateFrom(PatientInvitation invitation) {
        if (!id.equals(invitation.id().value())) {
            throw new IllegalArgumentException("Cannot update a different patient invitation");
        }
        patientCognitoSub = invitation.patientSubject().map(CognitoSubject::value).orElse(null);
        status = invitation.status().name();
        failureCode = invitation.failureCode().orElse(null);
        activatedAt = invitation.activatedAt().orElse(null);
        updatedAt = invitation.updatedAt();
    }

    public PatientInvitation toDomain() {
        return PatientInvitation.rehydrate(
                new InvitationId(id),
                new CognitoSubject(doctorCognitoSub),
                new EmailAddress(patientEmail),
                InvitationStatus.valueOf(status),
                patientCognitoSub == null ? null : new CognitoSubject(patientCognitoSub),
                failureCode,
                activatedAt,
                createdAt,
                updatedAt);
    }

    public UUID id() {
        return id;
    }
}

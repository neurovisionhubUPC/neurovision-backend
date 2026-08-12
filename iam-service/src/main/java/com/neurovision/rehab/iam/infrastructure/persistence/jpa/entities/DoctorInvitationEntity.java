package com.neurovision.rehab.iam.infrastructure.persistence.jpa.entities;

import com.neurovision.rehab.iam.domain.model.aggregates.DoctorInvitation;
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
@Table(name = "doctor_invitations")
public class DoctorInvitationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invited_by_admin_sub", nullable = false, length = 128, updatable = false)
    private String invitedByAdministrator;

    @Column(name = "doctor_cognito_sub", length = 128)
    private String doctorCognitoSub;

    @Column(name = "doctor_email", nullable = false, length = 254, updatable = false)
    private String doctorEmail;

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

    protected DoctorInvitationEntity() {}

    public static DoctorInvitationEntity fromDomain(DoctorInvitation invitation) {
        var entity = new DoctorInvitationEntity();
        entity.id = invitation.id().value();
        entity.invitedByAdministrator = invitation.invitedByAdministrator().value();
        entity.doctorEmail = invitation.doctorEmail().value();
        entity.createdAt = invitation.createdAt();
        entity.updateFrom(invitation);
        return entity;
    }

    public void updateFrom(DoctorInvitation invitation) {
        if (!id.equals(invitation.id().value())) {
            throw new IllegalArgumentException("Cannot update a different doctor invitation");
        }
        doctorCognitoSub = invitation.doctorSubject().map(CognitoSubject::value).orElse(null);
        status = invitation.status().name();
        failureCode = invitation.failureCode().orElse(null);
        activatedAt = invitation.activatedAt().orElse(null);
        updatedAt = invitation.updatedAt();
    }

    public DoctorInvitation toDomain() {
        return DoctorInvitation.rehydrate(
                new InvitationId(id),
                new CognitoSubject(invitedByAdministrator),
                new EmailAddress(doctorEmail),
                InvitationStatus.valueOf(status),
                doctorCognitoSub == null ? null : new CognitoSubject(doctorCognitoSub),
                failureCode,
                activatedAt,
                createdAt,
                updatedAt);
    }

    public UUID id() {
        return id;
    }
}

package com.neurovision.rehab.iam.domain.model.aggregates;

import com.neurovision.rehab.iam.domain.model.events.PatientInvitationCompleted;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PatientInvitation {

    private final InvitationId id;
    private final CognitoSubject invitedByDoctor;
    private final EmailAddress patientEmail;
    private final Instant createdAt;
    private final List<PatientInvitationCompleted> domainEvents = new ArrayList<>();

    private InvitationStatus status;
    private CognitoSubject patientSubject;
    private String failureCode;
    private Instant updatedAt;

    private PatientInvitation(
            InvitationId id,
            CognitoSubject invitedByDoctor,
            EmailAddress patientEmail,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "Invitation ID must not be null");
        this.invitedByDoctor = Objects.requireNonNull(invitedByDoctor, "Inviting doctor must not be null");
        this.patientEmail = Objects.requireNonNull(patientEmail, "Patient email must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time must not be null");
        this.updatedAt = createdAt;
        this.status = InvitationStatus.PENDING;
    }

    public static PatientInvitation initiate(
            InvitationId id,
            CognitoSubject invitedByDoctor,
            EmailAddress patientEmail,
            Instant createdAt) {
        return new PatientInvitation(id, invitedByDoctor, patientEmail, createdAt);
    }

    public static PatientInvitation rehydrate(
            InvitationId id,
            CognitoSubject invitedByDoctor,
            EmailAddress patientEmail,
            InvitationStatus status,
            CognitoSubject patientSubject,
            String failureCode,
            Instant createdAt,
            Instant updatedAt) {
        Objects.requireNonNull(status, "Invitation status must not be null");
        Objects.requireNonNull(updatedAt, "Update time must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Update time must not precede creation time");
        }

        validateRehydratedState(status, patientSubject, failureCode);
        var invitation = new PatientInvitation(id, invitedByDoctor, patientEmail, createdAt);
        invitation.status = status;
        invitation.patientSubject = patientSubject;
        invitation.failureCode = failureCode == null ? null : failureCode.trim();
        invitation.updatedAt = updatedAt;
        return invitation;
    }

    private static void validateRehydratedState(
            InvitationStatus status, CognitoSubject patientSubject, String failureCode) {
        boolean hasFailure = failureCode != null && !failureCode.isBlank();
        switch (status) {
            case PENDING -> {
                if (patientSubject != null || hasFailure) {
                    throw new IllegalArgumentException("Pending invitation state is inconsistent");
                }
            }
            case SENT -> {
                if (patientSubject == null || hasFailure) {
                    throw new IllegalArgumentException("Sent invitation state is inconsistent");
                }
            }
            case FAILED -> {
                if (patientSubject != null || !hasFailure) {
                    throw new IllegalArgumentException("Failed invitation state is inconsistent");
                }
            }
        }
    }

    public void markSent(CognitoSubject patientSubject, Instant occurredAt) {
        ensurePending();
        ensureChronological(occurredAt);

        this.patientSubject = Objects.requireNonNull(patientSubject, "Patient subject must not be null");
        this.status = InvitationStatus.SENT;
        this.updatedAt = occurredAt;
        this.domainEvents.add(new PatientInvitationCompleted(
                UUID.randomUUID(), id, invitedByDoctor, patientSubject, occurredAt));
    }

    public void markFailed(String failureCode, Instant occurredAt) {
        ensurePending();
        ensureChronological(occurredAt);
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("Failure code must not be blank");
        }

        this.failureCode = failureCode.trim();
        this.status = InvitationStatus.FAILED;
        this.updatedAt = occurredAt;
    }

    public List<PatientInvitationCompleted> pullDomainEvents() {
        var events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    private void ensurePending() {
        if (status != InvitationStatus.PENDING) {
            throw new IllegalStateException("Only a pending patient invitation can transition");
        }
    }

    private void ensureChronological(Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "Transition time must not be null");
        if (occurredAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Transition time must not precede the last update");
        }
    }

    public InvitationId id() {
        return id;
    }

    public CognitoSubject invitedByDoctor() {
        return invitedByDoctor;
    }

    public EmailAddress patientEmail() {
        return patientEmail;
    }

    public InvitationStatus status() {
        return status;
    }

    public Optional<CognitoSubject> patientSubject() {
        return Optional.ofNullable(patientSubject);
    }

    public Optional<String> failureCode() {
        return Optional.ofNullable(failureCode);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

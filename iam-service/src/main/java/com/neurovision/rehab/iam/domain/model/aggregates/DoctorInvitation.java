package com.neurovision.rehab.iam.domain.model.aggregates;

import com.neurovision.rehab.iam.domain.model.events.DoctorInvitationCompleted;
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

public final class DoctorInvitation {

    private final InvitationId id;
    private final CognitoSubject invitedByAdministrator;
    private final EmailAddress doctorEmail;
    private InvitationStatus status;
    private CognitoSubject doctorSubject;
    private String failureCode;
    private Instant activatedAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<DoctorInvitationCompleted> domainEvents = new ArrayList<>();

    private DoctorInvitation(
            InvitationId id,
            CognitoSubject invitedByAdministrator,
            EmailAddress doctorEmail,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "Invitation ID must not be null");
        this.invitedByAdministrator = Objects.requireNonNull(
                invitedByAdministrator, "Inviting administrator must not be null");
        this.doctorEmail = Objects.requireNonNull(doctorEmail, "Doctor email must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time must not be null");
        this.updatedAt = createdAt;
        this.status = InvitationStatus.PENDING;
    }

    public static DoctorInvitation initiate(
            InvitationId id,
            CognitoSubject invitedByAdministrator,
            EmailAddress doctorEmail,
            Instant createdAt) {
        return new DoctorInvitation(id, invitedByAdministrator, doctorEmail, createdAt);
    }

    public static DoctorInvitation rehydrate(
            InvitationId id,
            CognitoSubject invitedByAdministrator,
            EmailAddress doctorEmail,
            InvitationStatus status,
            CognitoSubject doctorSubject,
            String failureCode,
            Instant activatedAt,
            Instant createdAt,
            Instant updatedAt) {
        Objects.requireNonNull(status, "Invitation status must not be null");
        Objects.requireNonNull(createdAt, "Creation time must not be null");
        Objects.requireNonNull(updatedAt, "Update time must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Update time must not precede creation time");
        }
        if (activatedAt != null
                && (activatedAt.isBefore(createdAt) || activatedAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException(
                    "Activation time must be between creation and update time");
        }

        validateRehydratedState(status, doctorSubject, failureCode, activatedAt);
        var invitation =
                new DoctorInvitation(id, invitedByAdministrator, doctorEmail, createdAt);
        invitation.status = status;
        invitation.doctorSubject = doctorSubject;
        invitation.failureCode = failureCode == null ? null : failureCode.trim();
        invitation.activatedAt = activatedAt;
        invitation.updatedAt = updatedAt;
        return invitation;
    }

    private static void validateRehydratedState(
            InvitationStatus status,
            CognitoSubject doctorSubject,
            String failureCode,
            Instant activatedAt) {
        boolean hasFailure = failureCode != null && !failureCode.isBlank();
        switch (status) {
            case PENDING -> {
                if (doctorSubject != null || hasFailure || activatedAt != null) {
                    throw new IllegalArgumentException("Pending doctor invitation state is inconsistent");
                }
            }
            case INVITED -> {
                if (doctorSubject == null || hasFailure || activatedAt != null) {
                    throw new IllegalArgumentException("Invited doctor state is inconsistent");
                }
            }
            case ACTIVATED -> {
                if (doctorSubject == null || hasFailure || activatedAt == null) {
                    throw new IllegalArgumentException("Activated doctor state is inconsistent");
                }
            }
            case FAILED -> {
                if (doctorSubject != null || !hasFailure || activatedAt != null) {
                    throw new IllegalArgumentException("Failed doctor invitation state is inconsistent");
                }
            }
        }
    }

    public void markInvited(CognitoSubject doctorSubject, Instant occurredAt) {
        ensurePending();
        ensureChronological(occurredAt);

        this.doctorSubject =
                Objects.requireNonNull(doctorSubject, "Doctor subject must not be null");
        this.status = InvitationStatus.INVITED;
        this.updatedAt = occurredAt;
        this.domainEvents.add(new DoctorInvitationCompleted(
                UUID.randomUUID(), id, invitedByAdministrator, doctorSubject, occurredAt));
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

    public void markActivated(Instant occurredAt) {
        if (status == InvitationStatus.ACTIVATED) {
            return;
        }
        if (status != InvitationStatus.INVITED) {
            throw new IllegalStateException("Only an invited doctor account can be activated");
        }
        ensureChronological(occurredAt);
        status = InvitationStatus.ACTIVATED;
        activatedAt = occurredAt;
        updatedAt = occurredAt;
    }

    public List<DoctorInvitationCompleted> pullDomainEvents() {
        var events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    private void ensurePending() {
        if (status != InvitationStatus.PENDING) {
            throw new IllegalStateException("Only a pending doctor invitation can transition");
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

    public CognitoSubject invitedByAdministrator() {
        return invitedByAdministrator;
    }

    public EmailAddress doctorEmail() {
        return doctorEmail;
    }

    public InvitationStatus status() {
        return status;
    }

    public Optional<CognitoSubject> doctorSubject() {
        return Optional.ofNullable(doctorSubject);
    }

    public Optional<String> failureCode() {
        return Optional.ofNullable(failureCode);
    }

    public Optional<Instant> activatedAt() {
        return Optional.ofNullable(activatedAt);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

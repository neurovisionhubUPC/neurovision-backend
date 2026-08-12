package com.neurovision.rehab.iam.infrastructure.persistence.jpa.repositories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.iam.application.internal.commandservices.DoctorAlreadyInvitedException;
import com.neurovision.rehab.iam.application.ports.out.messaging.DoctorAccountInvitedV1;
import com.neurovision.rehab.iam.application.ports.out.messaging.DoctorInvitationOutbox;
import com.neurovision.rehab.iam.application.ports.out.persistence.DoctorInvitationRepository;
import com.neurovision.rehab.iam.domain.model.aggregates.DoctorInvitation;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import com.neurovision.rehab.iam.infrastructure.persistence.jpa.entities.DoctorInvitationEntity;
import com.neurovision.rehab.iam.infrastructure.persistence.jpa.entities.OutboxEventEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;

@ApplicationScoped
public class JpaDoctorInvitationRepository
        implements DoctorInvitationRepository, DoctorInvitationOutbox {

    private final DoctorInvitationPanacheRepository invitationEntities;
    private final OutboxEventPanacheRepository outboxEvents;
    private final ObjectMapper objectMapper;

    public JpaDoctorInvitationRepository(
            DoctorInvitationPanacheRepository invitationEntities,
            OutboxEventPanacheRepository outboxEvents,
            ObjectMapper objectMapper) {
        this.invitationEntities = Objects.requireNonNull(invitationEntities);
        this.outboxEvents = Objects.requireNonNull(outboxEvents);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public boolean existsActiveByDoctorEmail(EmailAddress doctorEmail) {
        Objects.requireNonNull(doctorEmail, "Doctor email must not be null");
        return invitationEntities.existsActiveByDoctorEmail(doctorEmail.value());
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<DoctorInvitation> findById(InvitationId invitationId) {
        Objects.requireNonNull(invitationId, "Invitation ID must not be null");
        return invitationEntities.findByIdOptional(invitationId.value())
                .map(DoctorInvitationEntity::toDomain);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<DoctorInvitation> findByDoctorSubject(CognitoSubject doctorSubject) {
        Objects.requireNonNull(doctorSubject, "Doctor subject must not be null");
        return invitationEntities
                .findByDoctorSubject(doctorSubject.value())
                .map(DoctorInvitationEntity::toDomain);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void save(DoctorInvitation invitation) {
        try {
            upsert(invitation);
            invitationEntities.flush();
        } catch (RuntimeException exception) {
            if (isActiveEmailConstraintViolation(exception)) {
                throw new DoctorAlreadyInvitedException();
            }
            throw exception;
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void saveAtomically(
            DoctorInvitation invitation, List<DoctorAccountInvitedV1> events) {
        Objects.requireNonNull(events, "Outbox events must not be null");
        upsert(invitation);
        events.stream()
                .map(event -> OutboxEventEntity.from(event, objectMapper))
                .forEach(outboxEvents::persist);
        invitationEntities.flush();
        outboxEvents.flush();
    }

    private void upsert(DoctorInvitation invitation) {
        Objects.requireNonNull(invitation, "Doctor invitation must not be null");
        invitationEntities.findByIdOptional(invitation.id().value())
                .ifPresentOrElse(
                        entity -> entity.updateFrom(invitation),
                        () -> invitationEntities.persist(DoctorInvitationEntity.fromDomain(invitation)));
    }

    private static boolean isActiveEmailConstraintViolation(Throwable exception) {
        for (var cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "uq_doctor_invitations_active_email".equals(violation.getConstraintName())) {
                return true;
            }
            if (cause.getMessage() != null
                    && cause.getMessage().contains("uq_doctor_invitations_active_email")) {
                return true;
            }
        }
        return false;
    }
}

package com.neurovision.rehab.iam.infrastructure.persistence.jpa.repositories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.iam.application.internal.commandservices.PatientAlreadyInvitedException;
import com.neurovision.rehab.iam.application.internal.queryservices.PatientInvitationPage;
import com.neurovision.rehab.iam.application.ports.out.messaging.PatientAccountInvitedV1;
import com.neurovision.rehab.iam.application.ports.out.persistence.PatientInvitationRepository;
import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import com.neurovision.rehab.iam.infrastructure.persistence.jpa.entities.OutboxEventEntity;
import com.neurovision.rehab.iam.infrastructure.persistence.jpa.entities.PatientInvitationEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;

@ApplicationScoped
public class JpaPatientInvitationRepository implements PatientInvitationRepository {

    private final PatientInvitationPanacheRepository invitationEntities;
    private final OutboxEventPanacheRepository outboxEvents;
    private final ObjectMapper objectMapper;

    public JpaPatientInvitationRepository(
            PatientInvitationPanacheRepository invitationEntities,
            OutboxEventPanacheRepository outboxEvents,
            ObjectMapper objectMapper) {
        this.invitationEntities = Objects.requireNonNull(invitationEntities);
        this.outboxEvents = Objects.requireNonNull(outboxEvents);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public boolean existsActiveByPatientEmail(EmailAddress patientEmail) {
        Objects.requireNonNull(patientEmail, "Patient email must not be null");
        return invitationEntities.existsActiveByPatientEmail(patientEmail.value());
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<PatientInvitation> findById(InvitationId invitationId) {
        Objects.requireNonNull(invitationId, "Invitation ID must not be null");
        return invitationEntities.findByIdOptional(invitationId.value())
                .map(PatientInvitationEntity::toDomain);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<PatientInvitation> findByPatientSubject(CognitoSubject patientSubject) {
        Objects.requireNonNull(patientSubject, "Patient subject must not be null");
        return invitationEntities
                .findByPatientSubject(patientSubject.value())
                .map(PatientInvitationEntity::toDomain);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public PatientInvitationPage findByDoctor(
            CognitoSubject doctorSubject, int page, int size, InvitationStatus status) {
        Objects.requireNonNull(doctorSubject, "Doctor subject must not be null");
        var query = invitationEntities.findByDoctor(
                doctorSubject.value(), status == null ? null : status.name(), page, size);
        var items = query.list().stream().map(PatientInvitationEntity::toDomain).toList();
        var totalElements = query.count();
        var totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PatientInvitationPage(items, page, size, totalElements, totalPages);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void save(PatientInvitation invitation) {
        try {
            upsert(invitation);
            invitationEntities.flush();
        } catch (RuntimeException exception) {
            if (isActiveEmailConstraintViolation(exception)) {
                throw new PatientAlreadyInvitedException();
            }
            throw exception;
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void saveWithOutboxEvents(
            PatientInvitation invitation, List<PatientAccountInvitedV1> events) {
        Objects.requireNonNull(events, "Outbox events must not be null");
        upsert(invitation);
        events.stream()
                .map(event -> OutboxEventEntity.from(event, objectMapper))
                .forEach(outboxEvents::persist);
        invitationEntities.flush();
        outboxEvents.flush();
    }

    private void upsert(PatientInvitation invitation) {
        Objects.requireNonNull(invitation, "Patient invitation must not be null");
        invitationEntities.findByIdOptional(invitation.id().value())
                .ifPresentOrElse(
                        entity -> entity.updateFrom(invitation),
                        () -> invitationEntities.persist(PatientInvitationEntity.fromDomain(invitation)));
    }

    private static boolean isActiveEmailConstraintViolation(Throwable exception) {
        for (var cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "uq_patient_invitations_active_email".equals(violation.getConstraintName())) {
                return true;
            }
            if (cause.getMessage() != null
                    && cause.getMessage().contains("uq_patient_invitations_active_email")) {
                return true;
            }
        }
        return false;
    }
}

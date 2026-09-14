package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapySession;
import java.util.Objects;

public final class TransitionTherapySessionCommandService {
    private final TherapySessionRepository repository;

    public TransitionTherapySessionCommandService(TherapySessionRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public TherapySession handle(TransitionTherapySessionCommand command) {
        Objects.requireNonNull(command);
        var session = repository.findByIdForUpdate(command.therapySessionId())
                .orElseThrow(() -> new IllegalArgumentException("Therapy session does not exist"));
        switch (command.action()) {
            case COMPLETE -> session.complete(command.occurredAt());
            case CANCEL -> session.cancel(command.reason(), command.occurredAt());
            case ABANDON -> session.abandon(command.reason(), command.occurredAt());
            case FAIL_TECHNICAL -> session.failTechnical(command.reason(), command.occurredAt());
            case EXPIRE_LEASE -> session.expireLease(command.occurredAt());
        }
        repository.save(session);
        return session;
    }
}

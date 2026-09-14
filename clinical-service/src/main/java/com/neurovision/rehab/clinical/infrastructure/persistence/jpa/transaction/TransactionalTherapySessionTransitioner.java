package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction;

import com.neurovision.rehab.clinical.application.internal.commandservices.TransitionTherapySessionCommand;
import com.neurovision.rehab.clinical.application.internal.commandservices.TransitionTherapySessionCommandService;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapySession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;

@ApplicationScoped
public class TransactionalTherapySessionTransitioner {
    private final TransitionTherapySessionCommandService delegate;
    public TransactionalTherapySessionTransitioner(TransitionTherapySessionCommandService delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }
    @Transactional
    public TherapySession handle(TransitionTherapySessionCommand command) { return delegate.handle(command); }
}

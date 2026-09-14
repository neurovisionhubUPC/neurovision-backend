package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction;

import com.neurovision.rehab.clinical.application.internal.commandservices.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;

@ApplicationScoped
public class TransactionalTherapySessionStarter {
    private final StartTherapySessionCommandService delegate;

    public TransactionalTherapySessionStarter(StartTherapySessionCommandService delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Transactional
    public StartTherapySessionResult handle(StartTherapySessionCommand command) {
        return delegate.handle(command);
    }
}

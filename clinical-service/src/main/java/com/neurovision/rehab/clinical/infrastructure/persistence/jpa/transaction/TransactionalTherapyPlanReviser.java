package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction;

import com.neurovision.rehab.clinical.application.internal.commandservices.*;
import com.neurovision.rehab.clinical.application.ports.in.TherapyPlanRevisionUseCase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;

@ApplicationScoped
public class TransactionalTherapyPlanReviser implements TherapyPlanRevisionUseCase {
    private final ReviseTherapyPlanCommandService delegate;
    public TransactionalTherapyPlanReviser(ReviseTherapyPlanCommandService delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }
    @Override
    @Transactional
    public ReviseTherapyPlanResult handle(ReviseTherapyPlanCommand command) {
        return delegate.handle(command);
    }
}

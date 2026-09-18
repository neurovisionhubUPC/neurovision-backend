package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction;

import com.neurovision.rehab.clinical.application.internal.commandservices.*;
import com.neurovision.rehab.clinical.application.ports.in.TherapyPlanCreationUseCase;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;

@ApplicationScoped
public class TransactionalTherapyPlanCreator implements TherapyPlanCreationUseCase {
    private final CreateTherapyPlanCommandService delegate;
    public TransactionalTherapyPlanCreator(CreateTherapyPlanCommandService delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }
    @Override
    @Transactional
    public TherapyPlan handle(CreateTherapyPlanCommand command) { return delegate.handle(command); }
}

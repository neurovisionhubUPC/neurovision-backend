package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction;

import com.neurovision.rehab.clinical.application.internal.commandservices.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;

@ApplicationScoped
public class TransactionalTherapeuticConfigurationAppender {
    private final AppendTherapeuticConfigurationCommandService delegate;

    public TransactionalTherapeuticConfigurationAppender(
            AppendTherapeuticConfigurationCommandService delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Transactional
    public TherapeuticExerciseConfiguration handle(AppendTherapeuticConfigurationCommand command) {
        return delegate.handle(command);
    }
}

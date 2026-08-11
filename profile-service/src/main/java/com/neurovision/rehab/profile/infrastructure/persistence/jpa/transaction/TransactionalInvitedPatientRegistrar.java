package com.neurovision.rehab.profile.infrastructure.persistence.jpa.transaction;

import com.neurovision.rehab.profile.application.internal.commandservices.RegisterInvitedPatientCommand;
import com.neurovision.rehab.profile.application.internal.commandservices.RegisterInvitedPatientCommandService;
import com.neurovision.rehab.profile.application.internal.commandservices.RegisterInvitedPatientOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;

@ApplicationScoped
public class TransactionalInvitedPatientRegistrar {

    private final RegisterInvitedPatientCommandService commandService;

    public TransactionalInvitedPatientRegistrar(
            RegisterInvitedPatientCommandService commandService) {
        this.commandService = Objects.requireNonNull(commandService);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public RegisterInvitedPatientOutcome handle(RegisterInvitedPatientCommand command) {
        return commandService.handle(command);
    }
}

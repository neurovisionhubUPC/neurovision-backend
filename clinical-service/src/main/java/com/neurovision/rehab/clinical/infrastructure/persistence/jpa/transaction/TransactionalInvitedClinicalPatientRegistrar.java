package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction;

import com.neurovision.rehab.clinical.application.internal.commandservices.RegisterInvitedClinicalPatientCommand;
import com.neurovision.rehab.clinical.application.internal.commandservices.RegisterInvitedClinicalPatientCommandService;
import com.neurovision.rehab.clinical.application.internal.commandservices.RegisterInvitedClinicalPatientOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;

@ApplicationScoped
public class TransactionalInvitedClinicalPatientRegistrar {

    private final RegisterInvitedClinicalPatientCommandService commandService;

    public TransactionalInvitedClinicalPatientRegistrar(
            RegisterInvitedClinicalPatientCommandService commandService) {
        this.commandService = Objects.requireNonNull(commandService);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public RegisterInvitedClinicalPatientOutcome handle(
            RegisterInvitedClinicalPatientCommand command) {
        return commandService.handle(command);
    }
}

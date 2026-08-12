package com.neurovision.rehab.profile.infrastructure.persistence.jpa.transaction;

import com.neurovision.rehab.profile.application.internal.commandservices.RegisterInvitedDoctorCommand;
import com.neurovision.rehab.profile.application.internal.commandservices.RegisterInvitedDoctorCommandService;
import com.neurovision.rehab.profile.application.internal.commandservices.RegisterInvitedDoctorOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;

@ApplicationScoped
public class TransactionalInvitedDoctorRegistrar {

    private final RegisterInvitedDoctorCommandService commandService;

    public TransactionalInvitedDoctorRegistrar(RegisterInvitedDoctorCommandService commandService) {
        this.commandService = Objects.requireNonNull(commandService);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public RegisterInvitedDoctorOutcome handle(RegisterInvitedDoctorCommand command) {
        return commandService.handle(command);
    }
}

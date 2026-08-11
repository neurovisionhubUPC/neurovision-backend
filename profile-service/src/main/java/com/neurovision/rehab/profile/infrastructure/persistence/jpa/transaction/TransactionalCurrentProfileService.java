package com.neurovision.rehab.profile.infrastructure.persistence.jpa.transaction;

import com.neurovision.rehab.profile.application.internal.commandservices.CompleteCurrentProfileCommand;
import com.neurovision.rehab.profile.application.internal.commandservices.CompleteCurrentProfileCommandService;
import com.neurovision.rehab.profile.application.internal.queryservices.GetCurrentProfileQuery;
import com.neurovision.rehab.profile.application.internal.queryservices.GetCurrentProfileQueryService;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;

@ApplicationScoped
public class TransactionalCurrentProfileService {

    private final GetCurrentProfileQueryService queryService;
    private final CompleteCurrentProfileCommandService commandService;

    public TransactionalCurrentProfileService(
            GetCurrentProfileQueryService queryService,
            CompleteCurrentProfileCommandService commandService) {
        this.queryService = Objects.requireNonNull(queryService);
        this.commandService = Objects.requireNonNull(commandService);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public UserProfile get(String cognitoSubject) {
        return queryService.handle(new GetCurrentProfileQuery(cognitoSubject));
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public UserProfile complete(CompleteCurrentProfileCommand command) {
        return commandService.handle(command);
    }
}

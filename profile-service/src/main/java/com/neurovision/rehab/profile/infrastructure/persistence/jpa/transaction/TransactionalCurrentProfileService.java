package com.neurovision.rehab.profile.infrastructure.persistence.jpa.transaction;

import com.neurovision.rehab.profile.application.internal.commandservices.CompleteCurrentProfileCommand;
import com.neurovision.rehab.profile.application.internal.commandservices.CompleteCurrentProfileCommandService;
import com.neurovision.rehab.profile.application.internal.commandservices.OnboardDoctorCommand;
import com.neurovision.rehab.profile.application.internal.commandservices.OnboardDoctorCommandService;
import com.neurovision.rehab.profile.application.internal.queryservices.CurrentProfile;
import com.neurovision.rehab.profile.application.internal.queryservices.GetCurrentProfileQuery;
import com.neurovision.rehab.profile.application.internal.queryservices.GetCurrentProfileQueryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;

@ApplicationScoped
public class TransactionalCurrentProfileService {

    private final GetCurrentProfileQueryService queryService;
    private final CompleteCurrentProfileCommandService commandService;
    private final OnboardDoctorCommandService onboardDoctorService;

    public TransactionalCurrentProfileService(
            GetCurrentProfileQueryService queryService,
            CompleteCurrentProfileCommandService commandService,
            OnboardDoctorCommandService onboardDoctorService) {
        this.queryService = Objects.requireNonNull(queryService);
        this.commandService = Objects.requireNonNull(commandService);
        this.onboardDoctorService = Objects.requireNonNull(onboardDoctorService);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public CurrentProfile get(String cognitoSubject) {
        return queryService.handle(new GetCurrentProfileQuery(cognitoSubject));
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public CurrentProfile complete(CompleteCurrentProfileCommand command) {
        return CurrentProfile.withoutProfessionalDetails(commandService.handle(command));
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public CurrentProfile onboardDoctor(OnboardDoctorCommand command) {
        var result = onboardDoctorService.handle(command);
        return CurrentProfile.withProfessionalDetails(
                result.userProfile(), result.doctorProfile());
    }
}

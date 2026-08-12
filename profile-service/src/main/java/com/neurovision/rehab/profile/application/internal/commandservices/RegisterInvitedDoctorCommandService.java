package com.neurovision.rehab.profile.application.internal.commandservices;

import com.neurovision.rehab.profile.application.ports.out.persistence.InboxEventRepository;
import com.neurovision.rehab.profile.application.ports.out.persistence.ProcessedInboxEvent;
import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileType;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class RegisterInvitedDoctorCommandService {

    private static final String EVENT_TYPE = "DoctorAccountInvited";
    private static final String EVENT_VERSION = "1";

    private final UserProfileRepository profileRepository;
    private final InboxEventRepository inboxRepository;
    private final Supplier<UUID> profileIdSupplier;
    private final Clock clock;

    public RegisterInvitedDoctorCommandService(
            UserProfileRepository profileRepository,
            InboxEventRepository inboxRepository,
            Supplier<UUID> profileIdSupplier,
            Clock clock) {
        this.profileRepository = Objects.requireNonNull(profileRepository);
        this.inboxRepository = Objects.requireNonNull(inboxRepository);
        this.profileIdSupplier = Objects.requireNonNull(profileIdSupplier);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Registers an invited doctor idempotently. Infrastructure must execute this operation in one
     * transaction so profile and inbox persistence remain atomic.
     */
    public RegisterInvitedDoctorOutcome handle(RegisterInvitedDoctorCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        if (inboxRepository.existsByEventId(command.eventId())) {
            return RegisterInvitedDoctorOutcome.DUPLICATE_EVENT;
        }

        var subject = new CognitoSubject(command.doctorSubject());
        var existingProfile = profileRepository.findByCognitoSubject(subject);
        if (existingProfile.isPresent()) {
            if (existingProfile.orElseThrow().type() != ProfileType.DOCTOR) {
                throw new ProfileTypeConflictException();
            }
            recordProcessedEvent(command);
            return RegisterInvitedDoctorOutcome.PROFILE_ALREADY_REGISTERED;
        }

        var profile = UserProfile.registerDoctor(
                new ProfileId(profileIdSupplier.get()), subject, command.occurredAt());
        profileRepository.save(profile);
        recordProcessedEvent(command);
        return RegisterInvitedDoctorOutcome.REGISTERED;
    }

    private void recordProcessedEvent(RegisterInvitedDoctorCommand command) {
        inboxRepository.save(new ProcessedInboxEvent(
                command.eventId(),
                EVENT_TYPE,
                EVENT_VERSION,
                command.occurredAt(),
                clock.instant()));
    }
}

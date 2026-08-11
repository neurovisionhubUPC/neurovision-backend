package com.neurovision.rehab.profile.application.internal.commandservices;

import com.neurovision.rehab.profile.application.ports.out.persistence.InboxEventRepository;
import com.neurovision.rehab.profile.application.ports.out.persistence.ProcessedInboxEvent;
import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class RegisterInvitedPatientCommandService {

    private static final String EVENT_TYPE = "PatientAccountInvited";
    private static final String EVENT_VERSION = "1";

    private final UserProfileRepository profileRepository;
    private final InboxEventRepository inboxRepository;
    private final Supplier<UUID> profileIdSupplier;
    private final Clock clock;

    public RegisterInvitedPatientCommandService(
            UserProfileRepository profileRepository,
            InboxEventRepository inboxRepository,
            Supplier<UUID> profileIdSupplier,
            Clock clock) {
        this.profileRepository =
                Objects.requireNonNull(profileRepository, "Profile repository must not be null");
        this.inboxRepository =
                Objects.requireNonNull(inboxRepository, "Inbox repository must not be null");
        this.profileIdSupplier =
                Objects.requireNonNull(profileIdSupplier, "Profile ID supplier must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    /**
     * Registers an invited patient idempotently. Infrastructure must execute this operation in a
     * single transaction so that profile and inbox persistence remain atomic.
     */
    public RegisterInvitedPatientOutcome handle(RegisterInvitedPatientCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        if (inboxRepository.existsByEventId(command.eventId())) {
            return RegisterInvitedPatientOutcome.DUPLICATE_EVENT;
        }

        var subject = new CognitoSubject(command.patientSubject());
        if (profileRepository.findByCognitoSubject(subject).isPresent()) {
            recordProcessedEvent(command);
            return RegisterInvitedPatientOutcome.PROFILE_ALREADY_REGISTERED;
        }

        var profile = UserProfile.registerInvitedPatient(
                new ProfileId(profileIdSupplier.get()), subject, command.occurredAt());
        profileRepository.save(profile);
        recordProcessedEvent(command);
        return RegisterInvitedPatientOutcome.REGISTERED;
    }

    private void recordProcessedEvent(RegisterInvitedPatientCommand command) {
        inboxRepository.save(new ProcessedInboxEvent(
                command.eventId(),
                EVENT_TYPE,
                EVENT_VERSION,
                command.occurredAt(),
                clock.instant()));
    }
}

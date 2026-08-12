package com.neurovision.rehab.profile.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.profile.application.ports.out.persistence.InboxEventRepository;
import com.neurovision.rehab.profile.application.ports.out.persistence.ProcessedInboxEvent;
import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileStatus;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegisterInvitedDoctorCommandServiceTest {

    private static final UUID EVENT_ID =
            UUID.fromString("8c560471-7dd4-4dc4-9249-b18d6545a174");
    private static final UUID PROFILE_ID =
            UUID.fromString("8f665873-4b23-4720-9b82-f13f334702ff");
    private static final String DOCTOR_SUBJECT = "doctor-cognito-sub";
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-11T21:04:30Z");
    private static final Instant PROCESSED_AT = Instant.parse("2026-08-11T21:05:00Z");

    private InMemoryUserProfileRepository profileRepository;
    private InMemoryInboxEventRepository inboxRepository;
    private RegisterInvitedDoctorCommandService service;

    @BeforeEach
    void setUp() {
        profileRepository = new InMemoryUserProfileRepository();
        inboxRepository = new InMemoryInboxEventRepository();
        service = new RegisterInvitedDoctorCommandService(
                profileRepository,
                inboxRepository,
                () -> PROFILE_ID,
                Clock.fixed(PROCESSED_AT, ZoneOffset.UTC));
    }

    @Test
    void shouldRegisterPendingDoctorProfileAndRecordTheProcessedEvent() {
        var result = service.handle(command(EVENT_ID));

        assertEquals(RegisterInvitedDoctorOutcome.REGISTERED, result);
        var profile = profileRepository.findByCognitoSubject(new CognitoSubject(DOCTOR_SUBJECT))
                .orElseThrow();
        assertEquals(new ProfileId(PROFILE_ID), profile.id());
        assertEquals(ProfileType.DOCTOR, profile.type());
        assertEquals(ProfileStatus.PENDING_COMPLETION, profile.status());
        assertEquals(OCCURRED_AT, profile.createdAt());

        var inboxEvent = inboxRepository.events.get(EVENT_ID);
        assertEquals("DoctorAccountInvited", inboxEvent.eventType());
        assertEquals("1", inboxEvent.eventVersion());
        assertEquals(OCCURRED_AT, inboxEvent.occurredAt());
        assertEquals(PROCESSED_AT, inboxEvent.processedAt());
    }

    @Test
    void shouldIgnoreAnEventThatWasAlreadyProcessed() {
        inboxRepository.events.put(
                EVENT_ID,
                new ProcessedInboxEvent(
                        EVENT_ID, "DoctorAccountInvited", "1", OCCURRED_AT, PROCESSED_AT));

        var result = service.handle(command(EVENT_ID));

        assertEquals(RegisterInvitedDoctorOutcome.DUPLICATE_EVENT, result);
        assertTrue(profileRepository.profiles.isEmpty());
        assertEquals(0, profileRepository.findCalls);
    }

    @Test
    void shouldRecordANewEventWithoutDuplicatingAnExistingDoctorProfile() {
        profileRepository.save(UserProfile.registerDoctor(
                new ProfileId(PROFILE_ID), new CognitoSubject(DOCTOR_SUBJECT), OCCURRED_AT));
        var secondEventId = UUID.fromString("9b126212-bb73-41c5-af65-175652543701");

        var result = service.handle(command(secondEventId));

        assertEquals(RegisterInvitedDoctorOutcome.PROFILE_ALREADY_REGISTERED, result);
        assertEquals(1, profileRepository.profiles.size());
        assertTrue(inboxRepository.existsByEventId(secondEventId));
    }

    @Test
    void shouldRejectAnExistingPatientIdentityWithoutRecordingTheEvent() {
        profileRepository.save(UserProfile.registerInvitedPatient(
                new ProfileId(PROFILE_ID), new CognitoSubject(DOCTOR_SUBJECT), OCCURRED_AT));

        assertThrows(ProfileTypeConflictException.class, () -> service.handle(command(EVENT_ID)));

        assertFalse(inboxRepository.existsByEventId(EVENT_ID));
    }

    @Test
    void shouldNotRecordTheEventWhenProfilePersistenceFails() {
        profileRepository.failOnSave = true;

        assertThrows(IllegalStateException.class, () -> service.handle(command(EVENT_ID)));

        assertFalse(inboxRepository.existsByEventId(EVENT_ID));
    }

    private static RegisterInvitedDoctorCommand command(UUID eventId) {
        return new RegisterInvitedDoctorCommand(eventId, DOCTOR_SUBJECT, OCCURRED_AT);
    }

    private static final class InMemoryUserProfileRepository implements UserProfileRepository {

        private final Map<CognitoSubject, UserProfile> profiles = new HashMap<>();
        private int findCalls;
        private boolean failOnSave;

        @Override
        public Optional<UserProfile> findByCognitoSubject(CognitoSubject cognitoSubject) {
            findCalls++;
            return Optional.ofNullable(profiles.get(cognitoSubject));
        }

        @Override
        public void save(UserProfile profile) {
            if (failOnSave) {
                throw new IllegalStateException("Simulated persistence failure");
            }
            profiles.put(profile.cognitoSubject(), profile);
        }
    }

    private static final class InMemoryInboxEventRepository implements InboxEventRepository {

        private final Map<UUID, ProcessedInboxEvent> events = new HashMap<>();

        @Override
        public boolean existsByEventId(UUID eventId) {
            return events.containsKey(eventId);
        }

        @Override
        public void save(ProcessedInboxEvent event) {
            events.put(event.eventId(), event);
        }
    }
}

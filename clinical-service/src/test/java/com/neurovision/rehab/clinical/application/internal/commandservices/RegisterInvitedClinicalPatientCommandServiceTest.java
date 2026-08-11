package com.neurovision.rehab.clinical.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.InboxEventRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.ProcessedInboxEvent;
import com.neurovision.rehab.clinical.domain.model.aggregates.ClinicalPatient;
import com.neurovision.rehab.clinical.domain.model.aggregates.DoctorPatientAssignment;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.DoctorPatientAssignmentId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegisterInvitedClinicalPatientCommandServiceTest {

    private static final UUID EVENT_ID =
            UUID.fromString("112e0134-5d78-47a4-965b-7572a54c47f4");
    private static final UUID PATIENT_ID =
            UUID.fromString("f018d2f8-1142-4f57-b1f3-bf18e2823b75");
    private static final UUID ASSIGNMENT_ID =
            UUID.fromString("5d799a35-2921-458b-ab65-dcf83598ef75");
    private static final String DOCTOR_SUBJECT = "7458e448-6051-7081-2745-186f682d626c";
    private static final String PATIENT_SUBJECT = "84d84438-f031-70ea-fd6e-3b4011bbb46e";
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-11T04:29:12Z");
    private static final Instant PROCESSED_AT = Instant.parse("2026-08-11T04:30:00Z");

    private InMemoryClinicalPatientRepository patientRepository;
    private InMemoryDoctorPatientAssignmentRepository assignmentRepository;
    private InMemoryInboxEventRepository inboxRepository;
    private RegisterInvitedClinicalPatientCommandService service;

    @BeforeEach
    void setUp() {
        patientRepository = new InMemoryClinicalPatientRepository();
        assignmentRepository = new InMemoryDoctorPatientAssignmentRepository();
        inboxRepository = new InMemoryInboxEventRepository();
        service = new RegisterInvitedClinicalPatientCommandService(
                patientRepository,
                assignmentRepository,
                inboxRepository,
                () -> PATIENT_ID,
                () -> ASSIGNMENT_ID,
                Clock.fixed(PROCESSED_AT, ZoneOffset.UTC));
    }

    @Test
    void shouldRegisterPatientAssignmentAndProcessedEvent() {
        var outcome = service.handle(command(EVENT_ID));

        assertEquals(RegisterInvitedClinicalPatientOutcome.REGISTERED, outcome);
        var patient = patientRepository.findByPatientSubject(new CognitoSubject(PATIENT_SUBJECT))
                .orElseThrow();
        assertEquals(new ClinicalPatientId(PATIENT_ID), patient.id());
        assertEquals(OCCURRED_AT, patient.createdAt());

        var assignment = assignmentRepository
                .findActiveByClinicalPatientIdAndDoctorSubject(
                        patient.id(), new CognitoSubject(DOCTOR_SUBJECT))
                .orElseThrow();
        assertEquals(new DoctorPatientAssignmentId(ASSIGNMENT_ID), assignment.id());
        assertEquals(OCCURRED_AT, assignment.assignedAt());

        assertProcessedEvent(EVENT_ID);
    }

    @Test
    void shouldIgnoreAnEventThatWasAlreadyProcessed() {
        inboxRepository.save(new ProcessedInboxEvent(
                EVENT_ID, "PatientAccountInvited", "1", OCCURRED_AT, PROCESSED_AT));

        var outcome = service.handle(command(EVENT_ID));

        assertEquals(RegisterInvitedClinicalPatientOutcome.DUPLICATE_EVENT, outcome);
        assertEquals(0, patientRepository.findCalls);
        assertEquals(0, assignmentRepository.findCalls);
    }

    @Test
    void shouldCreateOnlyTheMissingAssignmentForAnExistingPatient() {
        var patient = existingPatient();
        patientRepository.save(patient);
        var secondEventId = UUID.fromString("de59cf5e-7f91-4c92-947a-f4641515cbb0");

        var outcome = service.handle(command(secondEventId));

        assertEquals(RegisterInvitedClinicalPatientOutcome.ASSIGNMENT_CREATED, outcome);
        assertEquals(1, patientRepository.patients.size());
        assertTrue(assignmentRepository
                .findActiveByClinicalPatientIdAndDoctorSubject(
                        patient.id(), new CognitoSubject(DOCTOR_SUBJECT))
                .isPresent());
        assertProcessedEvent(secondEventId);
    }

    @Test
    void shouldRecordANewEventWithoutDuplicatingExistingClinicalData() {
        var patient = existingPatient();
        patientRepository.save(patient);
        assignmentRepository.save(DoctorPatientAssignment.assign(
                new DoctorPatientAssignmentId(ASSIGNMENT_ID),
                patient.id(),
                new CognitoSubject(DOCTOR_SUBJECT),
                OCCURRED_AT));
        var secondEventId = UUID.fromString("de59cf5e-7f91-4c92-947a-f4641515cbb0");

        var outcome = service.handle(command(secondEventId));

        assertEquals(RegisterInvitedClinicalPatientOutcome.ALREADY_REGISTERED, outcome);
        assertEquals(1, patientRepository.patients.size());
        assertEquals(1, assignmentRepository.assignments.size());
        assertProcessedEvent(secondEventId);
    }

    @Test
    void shouldNotRecordTheEventWhenPatientPersistenceFails() {
        patientRepository.failOnSave = true;

        assertThrows(IllegalStateException.class, () -> service.handle(command(EVENT_ID)));

        assertFalse(inboxRepository.existsByEventId(EVENT_ID));
        assertTrue(assignmentRepository.assignments.isEmpty());
    }

    @Test
    void shouldNotRecordTheEventWhenAssignmentPersistenceFails() {
        assignmentRepository.failOnSave = true;

        assertThrows(IllegalStateException.class, () -> service.handle(command(EVENT_ID)));

        assertFalse(inboxRepository.existsByEventId(EVENT_ID));
    }

    private void assertProcessedEvent(UUID eventId) {
        var event = inboxRepository.events.get(eventId);
        assertEquals("PatientAccountInvited", event.eventType());
        assertEquals("1", event.eventVersion());
        assertEquals(OCCURRED_AT, event.occurredAt());
        assertEquals(PROCESSED_AT, event.processedAt());
    }

    private static ClinicalPatient existingPatient() {
        return ClinicalPatient.registerInvited(
                new ClinicalPatientId(PATIENT_ID),
                new CognitoSubject(PATIENT_SUBJECT),
                OCCURRED_AT);
    }

    private static RegisterInvitedClinicalPatientCommand command(UUID eventId) {
        return new RegisterInvitedClinicalPatientCommand(
                eventId, DOCTOR_SUBJECT, PATIENT_SUBJECT, OCCURRED_AT);
    }

    private static final class InMemoryClinicalPatientRepository
            implements ClinicalPatientRepository {

        private final Map<CognitoSubject, ClinicalPatient> patients = new HashMap<>();
        private int findCalls;
        private boolean failOnSave;

        @Override
        public Optional<ClinicalPatient> findByPatientSubject(CognitoSubject patientSubject) {
            findCalls++;
            return Optional.ofNullable(patients.get(patientSubject));
        }

        @Override
        public void save(ClinicalPatient patient) {
            if (failOnSave) {
                throw new IllegalStateException("Simulated patient persistence failure");
            }
            patients.put(patient.patientSubject(), patient);
        }
    }

    private static final class InMemoryDoctorPatientAssignmentRepository
            implements DoctorPatientAssignmentRepository {

        private final Map<String, DoctorPatientAssignment> assignments = new HashMap<>();
        private int findCalls;
        private boolean failOnSave;

        @Override
        public Optional<DoctorPatientAssignment> findActiveByClinicalPatientIdAndDoctorSubject(
                ClinicalPatientId patientId, CognitoSubject doctorSubject) {
            findCalls++;
            return Optional.ofNullable(assignments.get(key(patientId, doctorSubject)));
        }

        @Override
        public void save(DoctorPatientAssignment assignment) {
            if (failOnSave) {
                throw new IllegalStateException("Simulated assignment persistence failure");
            }
            assignments.put(
                    key(assignment.clinicalPatientId(), assignment.doctorSubject()), assignment);
        }

        private static String key(ClinicalPatientId patientId, CognitoSubject doctorSubject) {
            return patientId.value() + ":" + doctorSubject.value();
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

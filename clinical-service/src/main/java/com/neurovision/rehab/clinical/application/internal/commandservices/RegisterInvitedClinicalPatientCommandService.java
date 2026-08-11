package com.neurovision.rehab.clinical.application.internal.commandservices;

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
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Registers an invited patient and the doctor-patient assignment idempotently.
 * Infrastructure must execute the patient, assignment, and inbox writes in one transaction.
 */
public final class RegisterInvitedClinicalPatientCommandService {

    private static final String EVENT_TYPE = "PatientAccountInvited";
    private static final String EVENT_VERSION = "1";

    private final ClinicalPatientRepository clinicalPatientRepository;
    private final DoctorPatientAssignmentRepository assignmentRepository;
    private final InboxEventRepository inboxEventRepository;
    private final Supplier<UUID> clinicalPatientIdSupplier;
    private final Supplier<UUID> assignmentIdSupplier;
    private final Clock clock;

    public RegisterInvitedClinicalPatientCommandService(
            ClinicalPatientRepository clinicalPatientRepository,
            DoctorPatientAssignmentRepository assignmentRepository,
            InboxEventRepository inboxEventRepository,
            Supplier<UUID> clinicalPatientIdSupplier,
            Supplier<UUID> assignmentIdSupplier,
            Clock clock) {
        this.clinicalPatientRepository =
                Objects.requireNonNull(clinicalPatientRepository, "Clinical patient repository must not be null");
        this.assignmentRepository =
                Objects.requireNonNull(assignmentRepository, "Assignment repository must not be null");
        this.inboxEventRepository =
                Objects.requireNonNull(inboxEventRepository, "Inbox repository must not be null");
        this.clinicalPatientIdSupplier =
                Objects.requireNonNull(clinicalPatientIdSupplier, "Clinical patient ID supplier must not be null");
        this.assignmentIdSupplier =
                Objects.requireNonNull(assignmentIdSupplier, "Assignment ID supplier must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    public RegisterInvitedClinicalPatientOutcome handle(
            RegisterInvitedClinicalPatientCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        if (inboxEventRepository.existsByEventId(command.eventId())) {
            return RegisterInvitedClinicalPatientOutcome.DUPLICATE_EVENT;
        }

        var patientSubject = new CognitoSubject(command.patientSubject());
        var doctorSubject = new CognitoSubject(command.doctorSubject());
        var existingPatient = clinicalPatientRepository.findByPatientSubject(patientSubject);
        var patientCreated = existingPatient.isEmpty();
        var clinicalPatient = existingPatient.orElseGet(() -> createPatient(command, patientSubject));
        if (patientCreated) {
            clinicalPatientRepository.save(clinicalPatient);
        }

        var existingAssignment = assignmentRepository
                .findActiveByClinicalPatientIdAndDoctorSubject(clinicalPatient.id(), doctorSubject);
        var assignmentCreated = existingAssignment.isEmpty();
        if (assignmentCreated) {
            assignmentRepository.save(DoctorPatientAssignment.assign(
                    new DoctorPatientAssignmentId(assignmentIdSupplier.get()),
                    clinicalPatient.id(),
                    doctorSubject,
                    command.occurredAt()));
        }

        inboxEventRepository.save(new ProcessedInboxEvent(
                command.eventId(),
                EVENT_TYPE,
                EVENT_VERSION,
                command.occurredAt(),
                clock.instant()));

        if (patientCreated) {
            return RegisterInvitedClinicalPatientOutcome.REGISTERED;
        }
        if (assignmentCreated) {
            return RegisterInvitedClinicalPatientOutcome.ASSIGNMENT_CREATED;
        }
        return RegisterInvitedClinicalPatientOutcome.ALREADY_REGISTERED;
    }

    private ClinicalPatient createPatient(
            RegisterInvitedClinicalPatientCommand command, CognitoSubject patientSubject) {
        return ClinicalPatient.registerInvited(
                new ClinicalPatientId(clinicalPatientIdSupplier.get()),
                patientSubject,
                command.occurredAt());
    }
}

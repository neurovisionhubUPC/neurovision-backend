package com.neurovision.rehab.clinical.domain.model.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.clinical.domain.model.valueobjects.AssignmentStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.DoctorPatientAssignmentId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DoctorPatientAssignmentTest {

    private static final DoctorPatientAssignmentId ASSIGNMENT_ID =
            new DoctorPatientAssignmentId(
                    UUID.fromString("5d799a35-2921-458b-ab65-dcf83598ef75"));
    private static final ClinicalPatientId PATIENT_ID =
            new ClinicalPatientId(UUID.fromString("f018d2f8-1142-4f57-b1f3-bf18e2823b75"));
    private static final CognitoSubject DOCTOR_SUBJECT =
            new CognitoSubject("7458e448-6051-7081-2745-186f682d626c");
    private static final Instant ASSIGNED_AT = Instant.parse("2026-08-11T05:00:00Z");

    @Test
    void shouldAssignTheInvitedPatientToTheInvitingDoctor() {
        var assignment = DoctorPatientAssignment.assign(
                ASSIGNMENT_ID, PATIENT_ID, DOCTOR_SUBJECT, ASSIGNED_AT);

        assertEquals(ASSIGNMENT_ID, assignment.id());
        assertEquals(PATIENT_ID, assignment.clinicalPatientId());
        assertEquals(DOCTOR_SUBJECT, assignment.doctorSubject());
        assertEquals(AssignmentStatus.ACTIVE, assignment.status());
        assertEquals(ASSIGNED_AT, assignment.assignedAt());
        assertTrue(assignment.endedAt().isEmpty());
    }

    @Test
    void shouldEndAnActiveAssignment() {
        var assignment = DoctorPatientAssignment.assign(
                ASSIGNMENT_ID, PATIENT_ID, DOCTOR_SUBJECT, ASSIGNED_AT);
        var endedAt = ASSIGNED_AT.plusSeconds(60);

        assignment.end(endedAt);

        assertEquals(AssignmentStatus.ENDED, assignment.status());
        assertEquals(endedAt, assignment.endedAt().orElseThrow());
    }

    @Test
    void shouldKeepEndingAnAssignmentIdempotent() {
        var assignment = DoctorPatientAssignment.assign(
                ASSIGNMENT_ID, PATIENT_ID, DOCTOR_SUBJECT, ASSIGNED_AT);
        var endedAt = ASSIGNED_AT.plusSeconds(60);
        assignment.end(endedAt);

        assignment.end(endedAt.plusSeconds(60));

        assertEquals(endedAt, assignment.endedAt().orElseThrow());
    }

    @Test
    void shouldRejectAnEndTimeBeforeAssignment() {
        var assignment = DoctorPatientAssignment.assign(
                ASSIGNMENT_ID, PATIENT_ID, DOCTOR_SUBJECT, ASSIGNED_AT);

        assertThrows(
                IllegalArgumentException.class,
                () -> assignment.end(ASSIGNED_AT.minusSeconds(1)));
    }

    @Test
    void shouldRejectInconsistentRehydratedState() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DoctorPatientAssignment.rehydrate(
                        ASSIGNMENT_ID,
                        PATIENT_ID,
                        DOCTOR_SUBJECT,
                        AssignmentStatus.ACTIVE,
                        ASSIGNED_AT,
                        ASSIGNED_AT.plusSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> DoctorPatientAssignment.rehydrate(
                        ASSIGNMENT_ID,
                        PATIENT_ID,
                        DOCTOR_SUBJECT,
                        AssignmentStatus.ENDED,
                        ASSIGNED_AT,
                        null));
    }

    @Test
    void shouldRejectAnEndTimeBeforeAssignmentWhenRehydrating() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DoctorPatientAssignment.rehydrate(
                        ASSIGNMENT_ID,
                        PATIENT_ID,
                        DOCTOR_SUBJECT,
                        AssignmentStatus.ENDED,
                        ASSIGNED_AT,
                        ASSIGNED_AT.minusSeconds(1)));
    }

    @Test
    void shouldRejectMissingAssignmentData() {
        assertThrows(
                NullPointerException.class,
                () -> DoctorPatientAssignment.assign(
                        null, PATIENT_ID, DOCTOR_SUBJECT, ASSIGNED_AT));
        assertThrows(
                NullPointerException.class,
                () -> DoctorPatientAssignment.assign(
                        ASSIGNMENT_ID, null, DOCTOR_SUBJECT, ASSIGNED_AT));
        assertThrows(
                NullPointerException.class,
                () -> DoctorPatientAssignment.assign(
                        ASSIGNMENT_ID, PATIENT_ID, null, ASSIGNED_AT));
    }
}

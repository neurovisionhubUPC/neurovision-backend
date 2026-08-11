package com.neurovision.rehab.clinical.domain.model.valueobjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClinicalValueObjectsTest {

    @Test
    void shouldNormalizeClinicalTextAndCognitoSubjects() {
        assertEquals("patient-sub", new CognitoSubject("  patient-sub  ").value());
        assertEquals("Ischemic stroke", new StrokeDiagnosis("  Ischemic stroke  ").value());
        assertEquals("Visual neglect", new Sequela("  Visual neglect  ").value());
    }

    @Test
    void shouldRejectBlankClinicalTextAndCognitoSubjects() {
        assertThrows(IllegalArgumentException.class, () -> new CognitoSubject(" "));
        assertThrows(IllegalArgumentException.class, () -> new StrokeDiagnosis(" "));
        assertThrows(IllegalArgumentException.class, () -> new Sequela(" "));
    }

    @Test
    void shouldRejectMissingIdentifiers() {
        assertThrows(NullPointerException.class, () -> new ClinicalPatientId(null));
        assertThrows(NullPointerException.class, () -> new DoctorPatientAssignmentId(null));
    }

    @Test
    void shouldExposeIdentifierValues() {
        var patientId = UUID.fromString("f018d2f8-1142-4f57-b1f3-bf18e2823b75");
        var assignmentId = UUID.fromString("5d799a35-2921-458b-ab65-dcf83598ef75");

        assertEquals(patientId, new ClinicalPatientId(patientId).value());
        assertEquals(assignmentId, new DoctorPatientAssignmentId(assignmentId).value());
    }
}

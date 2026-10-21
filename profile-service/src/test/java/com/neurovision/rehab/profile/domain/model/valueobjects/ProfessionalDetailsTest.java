package com.neurovision.rehab.profile.domain.model.valueobjects;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfessionalDetailsTest {
    @Test void preservesLegacyStoredLicensesWithoutAcceptingThemOnSubmission() {
        assertEquals("CMP-123456", ProfessionalDetails.fromStored("Neurology", "CMP-123456").medicalLicense());
        assertThrows(IllegalArgumentException.class, () -> new ProfessionalDetails("Neurology", "CMP-123456"));
        assertEquals(new ProfessionalDetails(null, "012345"), new ProfessionalDetails(null, "012345"));
    }

    @Test void completionDoesNotRequireProfessionalFields() {
        var details = new ProfessionalDetails(null, null);
        assertNull(details.specialty());
        assertNull(details.medicalLicense());
    }
    @Test void acceptsSixAsciiDigitsAndPreservesLeadingZeros() {
        assertEquals("012345", new ProfessionalDetails(null, " 012345 ").medicalLicense());
        assertEquals("123456", new ProfessionalDetails("Neurology", "123456").medicalLicense());
    }
    @Test void rejectsEveryDisallowedCmpShape() {
        for (var value : new String[]{"", " ", "000000", "12345", "1234567", "CMP123456", "123 45", "123-45", "123*45", "123#45", "１２３４５６", "١٢٣٤٥٦", "12345\n6"}) {
            assertThrows(IllegalArgumentException.class, () -> new ProfessionalDetails("Neurology", value));
        }
    }
}

package com.neurovision.rehab.profile.domain.model.valueobjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PersonalDetailsTest {

    @Test
    void shouldNormalizePersonalDetails() {
        var details = new PersonalDetails(
                "  Andrea ", " Salazar  ", " 74859621 ", LocalDate.of(1998, 4, 12), " +51 987 654 321 ");

        assertEquals("Andrea", details.firstName());
        assertEquals("Salazar", details.lastName());
        assertEquals("74859621", details.documentNumber());
        assertEquals("+51 987 654 321", details.phone());
    }

    @Test
    void shouldRejectInvalidPersonalDetails() {
        var birthDate = LocalDate.of(1998, 4, 12);

        assertThrows(
                IllegalArgumentException.class,
                () -> new PersonalDetails(" ", "Salazar", "74859621", birthDate, "+51987654321"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersonalDetails("Andrea", "Salazar", "12", birthDate, "+51987654321"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersonalDetails(
                        "Andrea",
                        "Salazar",
                        "74859621",
                        LocalDate.now(ZoneOffset.UTC).plusDays(1),
                        "+51987654321"));
    }
}

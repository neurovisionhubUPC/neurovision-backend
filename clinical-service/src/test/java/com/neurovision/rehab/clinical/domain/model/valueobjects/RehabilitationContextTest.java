package com.neurovision.rehab.clinical.domain.model.valueobjects;

import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.domain.model.valueobjects.RehabilitationContext.Change.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RehabilitationContextTest {
    RehabilitationContext original() {
        return RehabilitationContext.rehydrate(RehabilitationContext.Hemifield.LEFT,
                "Previously established diagnosis", List.of("Reported sequela"), "Original notes");
    }

    @Test void suppliedValuesAreManualOptionalAndTrimmed() {
        var changed = original().apply(new RehabilitationContext.Patch(
                set(RehabilitationContext.Hemifield.RIGHT), set("  Diagnóstico informado  "),
                set(List.of("  Secuela α  ", "Secuela β")), set("  Note\nline  ")));
        assertEquals(RehabilitationContext.Hemifield.RIGHT, changed.affectedHemifield());
        assertEquals("Diagnóstico informado", changed.previousDiagnosis());
        assertEquals(List.of("Secuela α", "Secuela β"), changed.sequelae());
        assertEquals("Note\nline", changed.observations());
    }

    @Test void nullClearsAndEmptyListDoesNotClaimClinicalAbsence() {
        var cleared = original().apply(new RehabilitationContext.Patch(
                set(null), set(null), set(null), set("   ")));
        assertNull(cleared.affectedHemifield());
        assertNull(cleared.previousDiagnosis());
        assertEquals(List.of(), cleared.sequelae());
        assertNull(cleared.observations());
        assertEquals(List.of(), original().apply(new RehabilitationContext.Patch(
                absent(), absent(), set(List.of()), absent())).sequelae());
    }

    @Test void omittedFieldsPreserveLegacyValuesWithoutNewLengthLimits() {
        var legacy = RehabilitationContext.rehydrate(null, "Existing diagnosis",
                List.of("x".repeat(1500), "Other legacy value"), null);
        var changed = legacy.apply(new RehabilitationContext.Patch(
                absent(), absent(), absent(), set("New notes")));
        assertEquals(legacy.sequelae(), changed.sequelae());
        assertEquals(legacy.previousDiagnosis(), changed.previousDiagnosis());
        assertNull(changed.affectedHemifield());
    }

    @Test void suppliedTextLimitsUseUnicodeCodePoints() {
        var diagnosis = "😀".repeat(500);
        var notes = "α".repeat(2000);
        var valid = original().apply(new RehabilitationContext.Patch(
                absent(), set(diagnosis), set(List.of("s".repeat(600), "t".repeat(400))), set(notes)));
        assertEquals(diagnosis, valid.previousDiagnosis());
        assertEquals(notes, valid.observations());
        for (var patch : List.of(
                new RehabilitationContext.Patch(absent(), set(diagnosis + "x"), absent(), absent()),
                new RehabilitationContext.Patch(absent(), absent(), set(List.of("s".repeat(1001))), absent()),
                new RehabilitationContext.Patch(absent(), absent(), absent(), set(notes + "x")))) {
            assertThrows(IllegalArgumentException.class, () -> original().apply(patch));
        }
    }

    @Test void malformedProvidedTextRejectsWithoutChangingOriginal() {
        var old = original();
        for (String invalid : List.of("NUL\u0000value", "Control\u0001", "Bad\uD800")) {
            assertThrows(IllegalArgumentException.class, () -> old.apply(
                    new RehabilitationContext.Patch(absent(), set(invalid), absent(), absent())));
        }
        for (var entries : List.of(List.of(" "), List.of("same", " same "))) {
            assertThrows(IllegalArgumentException.class, () -> old.apply(
                    new RehabilitationContext.Patch(absent(), absent(), set(entries), absent())));
        }
        assertEquals(original(), old);
    }

    @Test void valuesAndPatchDoNotExposeClinicalTextInDiagnostics() {
        var values = new ArrayList<>(List.of("private note"));
        var patch = new RehabilitationContext.Patch(absent(), absent(), set(values), absent());
        values.clear();
        assertEquals(List.of("private note"), patch.sequelae().value());
        assertFalse(original().toString().contains("Original notes"));
        assertFalse(patch.toString().contains("private note"));
        assertThrows(IllegalArgumentException.class, () -> new RehabilitationContext.Patch(
                absent(), absent(), absent(), absent()));
    }
}

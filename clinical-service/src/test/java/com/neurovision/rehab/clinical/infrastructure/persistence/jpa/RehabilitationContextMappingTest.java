package com.neurovision.rehab.clinical.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.domain.model.valueobjects.RehabilitationContext.Change.*;

import com.neurovision.rehab.clinical.domain.model.aggregates.ClinicalPatient;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.ClinicalPatientEntity;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RehabilitationContextMappingTest {
    @Test void optionalContextReusesAssessmentAndDoesNotChangeIdentityOrActiveStatus() {
        var original = ClinicalPatient.registerInvited(new ClinicalPatientId(new UUID(0, 1)),
                new CognitoSubject("patient"), Instant.EPOCH);
        var entity = ClinicalPatientEntity.fromDomain(original);
        var context = entity.rehabilitationContext().apply(new RehabilitationContext.Patch(
                set(RehabilitationContext.Hemifield.LEFT), set("Manual prior diagnosis"),
                set(List.of("Reported sequela")), set("Manual notes")));
        assertTrue(entity.replaceRehabilitationContext(context, Instant.EPOCH.plusSeconds(10)));
        assertEquals(context, entity.rehabilitationContext());
        var actual = entity.toDomain();
        assertEquals(original.id(), actual.id());
        assertEquals(original.patientSubject(), actual.patientSubject());
        assertEquals(ClinicalPatientStatus.ACTIVE, actual.status());
        assertEquals(original.createdAt(), actual.createdAt());
        assertEquals("Manual prior diagnosis", actual.strokeDiagnosis().orElseThrow().value());
        assertEquals(Set.of(new Sequela("Reported sequela")), actual.sequelae());
        assertEquals(Instant.EPOCH.plusSeconds(10), actual.updatedAt());
        assertFalse(entity.replaceRehabilitationContext(context, Instant.EPOCH.plusSeconds(20)));
        assertEquals(Instant.EPOCH.plusSeconds(10), entity.toDomain().updatedAt());
        var empty = context.apply(new RehabilitationContext.Patch(set(null), set(null), set(null), set(null)));
        assertTrue(entity.replaceRehabilitationContext(empty, Instant.EPOCH.plusSeconds(30)));
        assertTrue(entity.toDomain().strokeDiagnosis().isEmpty());
        assertTrue(entity.toDomain().sequelae().isEmpty());
        assertEquals(ClinicalPatientStatus.ACTIVE, entity.toDomain().status());
    }

    @Test void existingDomainWriterPreservesNewContextAndLegacyArraysAreNotTruncated() {
        var patient = ClinicalPatient.rehydrate(new ClinicalPatientId(new UUID(0, 1)),
                new CognitoSubject("patient"), ClinicalPatientStatus.ACTIVE,
                new StrokeDiagnosis("Legacy diagnosis"), Set.of(new Sequela("x".repeat(1500))),
                Instant.EPOCH, Instant.EPOCH);
        var entity = ClinicalPatientEntity.fromDomain(patient);
        var changed = entity.rehabilitationContext().apply(new RehabilitationContext.Patch(
                set(RehabilitationContext.Hemifield.RIGHT), absent(), absent(), set("notes")));
        entity.replaceRehabilitationContext(changed, Instant.EPOCH.plusSeconds(10));
        entity.updateFrom(entity.toDomain());
        assertEquals(changed, entity.rehabilitationContext());
        assertEquals(1500, entity.rehabilitationContext().sequelae().getFirst().length());
    }

    @Test void rejectedLegacyOrBackdatedEditLeavesEveryContextValueUnchanged() {
        for (var status : ClinicalPatientStatus.values()) {
            var patient = ClinicalPatient.rehydrate(new ClinicalPatientId(new UUID(0, 1)),
                    new CognitoSubject("patient"), status, null, Set.of(), Instant.EPOCH, Instant.EPOCH);
            var entity = ClinicalPatientEntity.fromDomain(patient);
            var original = entity.rehabilitationContext();
            var desired = original.apply(new RehabilitationContext.Patch(absent(), absent(), absent(), set("notes")));
            assertThrows(RuntimeException.class, () -> entity.replaceRehabilitationContext(desired,
                    status == ClinicalPatientStatus.ACTIVE ? Instant.EPOCH.minusSeconds(1) : Instant.EPOCH));
            assertEquals(original, entity.rehabilitationContext());
            assertEquals(status, entity.toDomain().status());
            assertEquals(Instant.EPOCH, entity.toDomain().updatedAt());
        }
    }

    @Test void omittedRawLegacyArrayKeepsOrderSpacingDuplicatesAndLongValues() throws Exception {
        var entity = ClinicalPatientEntity.fromDomain(ClinicalPatient.registerInvited(
                new ClinicalPatientId(new UUID(0, 1)), new CognitoSubject("patient"), Instant.EPOCH));
        var field = ClinicalPatientEntity.class.getDeclaredField("sequelae");
        field.setAccessible(true);
        var raw = new String[]{" z ", "x".repeat(1500), "duplicate", "duplicate"};
        field.set(entity, raw.clone());
        var context = entity.rehabilitationContext();
        var desired = context.apply(new RehabilitationContext.Patch(
                absent(), absent(), absent(), set("notes only")));
        entity.replaceRehabilitationContext(desired, Instant.EPOCH.plusSeconds(1));
        assertEquals(List.of(raw), entity.rehabilitationContext().sequelae());
        assertArrayEquals(raw, (String[]) field.get(entity));
    }
}

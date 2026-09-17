package com.neurovision.rehab.clinical.application.internal.commandservices;

import static com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppendTherapeuticConfigurationCommandServiceTest {
    private final TherapyPlanId planId = new TherapyPlanId(UUID.randomUUID());
    private final ClinicalPatientId patientId = new ClinicalPatientId(UUID.randomUUID());
    private final CognitoSubject doctor = new CognitoSubject("doctor-subject");
    private final Instant now = Instant.parse("2026-09-14T00:00:00Z");
    private FakeConfigurationRepository configurations;
    private FakeAssignments assignments;
    private AppendTherapeuticConfigurationCommandService service;
    private List<String> calls;

    @BeforeEach
    void setUp() {
        configurations = new FakeConfigurationRepository();
        calls = new ArrayList<>();
        configurations.calls = calls;
        assignments = new FakeAssignments(true, calls);
        var plan = TherapyPlan.rehydrate(planId, patientId, doctor, TherapyPlanStatus.ACTIVE,
                1, 2, 30, null, 5, 15, 50,
                Map.of(TherapeuticExercise.FIXATION.code(), 1, TherapeuticExercise.VISUAL_SEARCH.code(), 1));
        service = new AppendTherapeuticConfigurationCommandService(
                new TherapyPlanRepository() {
                    @Override public Optional<TherapyPlan> findById(TherapyPlanId id) {
                        return id.equals(planId) ? Optional.of(plan) : Optional.empty();
                    }
                    @Override public Optional<TherapyPlan> findByIdForUpdate(TherapyPlanId id) {
                        calls.add("plan-lock");
                        return findById(id);
                    }
                    @Override public void save(TherapyPlan ignored) {}
                }, assignments,
                configurations, UUID::randomUUID,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void assignedDoctorAppendsIndependentImmutableVersions() {
        var first = service.handle(command(doctor, patientId));
        var second = service.handle(command(doctor, patientId));
        var search = service.handle(new AppendTherapeuticConfigurationCommand(
                planId, patientId, doctor, TherapeuticExercise.VISUAL_SEARCH, 1, now,
                new VisualSearch(VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                        AffectedHemifield.LEFT, 10, 5, 500, 5000,
                        new java.math.BigDecimal("0.75"), 50, 2)));
        assertAll(() -> assertEquals(1, first.version()), () -> assertEquals(2, second.version()),
                () -> assertEquals(1, search.version()),
                () -> assertEquals(3, configurations.values.size()),
                () -> assertEquals(doctor, second.createdByDoctorSubject()));
        assertEquals(List.of("plan-lock", "assignment-lock", "latest-version", "append"), calls.subList(0, 4));
    }

    @Test
    void rejectsDoctorMismatchPatientMismatchAndRevokedAssignment() {
        assertThrows(SecurityException.class, () -> service.handle(command(new CognitoSubject("other"), patientId)));
        assertThrows(SecurityException.class, () -> service.handle(command(doctor, new ClinicalPatientId(UUID.randomUUID()))));
        assignments.active = false;
        assertThrows(SecurityException.class, () -> service.handle(command(doctor, patientId)));
        assertTrue(configurations.values.isEmpty());
    }

    @Test
    void rejectsExerciseOutsidePlanCatalogBinding() {
        var command = new AppendTherapeuticConfigurationCommand(planId, patientId, doctor,
                TherapeuticExercise.VISUAL_SCANNING, 1, now,
                new VisualScanning(VisualScanningMode.STANDARD_SCANNING, AffectedHemifield.LEFT,
                        500, 4000, 10, 50, 10, 1000, 2));
        assertThrows(IllegalStateException.class, () -> service.handle(command));
    }

    private AppendTherapeuticConfigurationCommand command(CognitoSubject subject, ClinicalPatientId patient) {
        return new AppendTherapeuticConfigurationCommand(planId, patient, subject,
                TherapeuticExercise.FIXATION, 1, now,
                new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION, TargetPosition.CENTER,
                        500, 1000, 50, 10, 800, 2));
    }

    private static final class FakeAssignments implements DoctorPatientAssignmentRepository {
        private boolean active;
        private final List<String> calls;
        private FakeAssignments(boolean active, List<String> calls) { this.active = active; this.calls = calls; }
        @Override public Optional<DoctorPatientAssignment> findActiveByClinicalPatientIdAndDoctorSubject(
                ClinicalPatientId patient, CognitoSubject doctor) {
            return active ? Optional.of(DoctorPatientAssignment.assign(
                    new DoctorPatientAssignmentId(UUID.randomUUID()), patient, doctor, Instant.EPOCH)) : Optional.empty();
        }
        @Override public Optional<DoctorPatientAssignment> findActiveByClinicalPatientIdAndDoctorSubjectForUpdate(
                ClinicalPatientId patient, CognitoSubject doctor) {
            calls.add("assignment-lock");
            return findActiveByClinicalPatientIdAndDoctorSubject(patient, doctor);
        }
        @Override public void save(DoctorPatientAssignment assignment) {}
    }

    private static final class FakeConfigurationRepository implements TherapeuticExerciseConfigurationRepository {
        private final List<TherapeuticExerciseConfiguration> values = new ArrayList<>();
        private List<String> calls = List.of();
        @Override public OptionalLong findLatestVersion(TherapyPlanId plan, TherapeuticExercise exercise) {
            calls.add("latest-version");
            return values.stream().filter(value -> value.therapyPlanId().equals(plan) && value.exercise() == exercise)
                    .mapToLong(TherapeuticExerciseConfiguration::version).max();
        }
        @Override public Optional<TherapeuticExerciseConfiguration> findEffective(
                TherapyPlanId plan, TherapeuticExercise exercise, Instant effectiveAt) {
            return values.stream().filter(value -> value.therapyPlanId().equals(plan))
                    .filter(value -> value.exercise() == exercise)
                    .filter(value -> !value.effectiveFrom().isAfter(effectiveAt))
                    .max(java.util.Comparator.comparing(TherapeuticExerciseConfiguration::effectiveFrom)
                            .thenComparingLong(TherapeuticExerciseConfiguration::version));
        }
        @Override public void append(TherapeuticExerciseConfiguration configuration) {
            calls.add("append");
            boolean duplicate = values.stream().anyMatch(value -> value.therapyPlanId().equals(configuration.therapyPlanId())
                    && value.exercise() == configuration.exercise() && value.version() == configuration.version());
            if (duplicate) throw new IllegalStateException("Duplicate immutable version");
            values.add(configuration);
        }
    }
}

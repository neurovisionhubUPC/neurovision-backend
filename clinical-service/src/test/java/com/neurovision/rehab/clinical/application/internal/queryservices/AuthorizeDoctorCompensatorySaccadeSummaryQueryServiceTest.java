package com.neurovision.rehab.clinical.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorVisualSearchSummarySnapshotRepository;
import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorVisualSearchSummarySnapshotRepository.Snapshot;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AuthorizeDoctorCompensatorySaccadeSummaryQueryServiceTest {
    static final TherapySessionId SESSION = new TherapySessionId(new UUID(0, 1));
    static final UUID CONFIGURATION = new UUID(0, 2);
    static TherapeuticExerciseParameters.VisualScanning scanning() {
        return new TherapeuticExerciseParameters.VisualScanning(TherapeuticExerciseParameters.VisualScanningMode.COMPENSATORY_SACCADE,
                TherapeuticExerciseParameters.AffectedHemifield.LEFT, 500, 3000, 10, 50, 5, 500, 1);
    }
    static TherapeuticConfigurationSnapshot configuration(TherapeuticExerciseParameters parameters) {
        return new TherapeuticConfigurationSnapshot(new TherapeuticConfigurationId(CONFIGURATION), 3,
                parameters.exercise(), parameters.schemaVersion(), parameters);
    }
    @Test void returnsOnlyTheFrozenPolicyAndDoesNotCacheEarlierPermission() {
        var calls = new AtomicInteger();
        var query = new AuthorizeDoctorCompensatorySaccadeSummaryQueryService((doctor, id) -> {
            assertEquals("synthetic-doctor", doctor.value()); assertEquals(SESSION, id);
            return calls.incrementAndGet() == 1 ? Optional.of(new Snapshot(SESSION, configuration(scanning()))) : Optional.empty();
        });
        var permit = query.authorize("synthetic-doctor", SESSION);
        assertEquals(SESSION.value(), permit.therapySessionId()); assertEquals(CONFIGURATION, permit.configurationId());
        assertEquals(3, permit.configurationVersion()); assertEquals(500, permit.minimumFixationMs()); assertEquals(3000, permit.maximumStimulusDurationMs());
        assertFalse(permit.toString().contains(SESSION.value().toString()));
        assertThrows(SecurityException.class, () -> query.authorize("synthetic-doctor", SESSION)); assertEquals(2, calls.get());
    }
    @Test void missingOrNonCanonicalIdentityNeverQueriesAndMissingPermissionIsDenied() {
        var calls = new AtomicInteger();
        var query = new AuthorizeDoctorCompensatorySaccadeSummaryQueryService((doctor, id) -> { calls.incrementAndGet(); return Optional.empty(); });
        for (String subject : Arrays.asList(null, "", " ", " synthetic-doctor "))
            assertThrows(SecurityException.class, () -> query.authorize(subject, SESSION));
        assertThrows(SecurityException.class, () -> query.authorize("synthetic-doctor", null)); assertEquals(0, calls.get());
        assertThrows(SecurityException.class, () -> query.authorize("synthetic-doctor", SESSION)); assertEquals(1, calls.get());
    }
    @Test void unavailableAndCrossSessionObservationsCannotBecomePermitsOrDenials() {
        for (int kind = 0; kind < 3; kind++) {
            final int mode = kind;
            var query = new AuthorizeDoctorCompensatorySaccadeSummaryQueryService((doctor, id) -> switch (mode) {
                case 0 -> throw new IllegalStateException("private");
                case 1 -> null;
                default -> Optional.of(new Snapshot(new TherapySessionId(new UUID(0, 9)), configuration(scanning())));
            });
            var error = assertThrows(IllegalStateException.class, () -> query.authorize("synthetic-doctor", SESSION));
            assertEquals("Compensatory Saccade summary authorization unavailable", error.getMessage()); assertNull(error.getCause());
        }
    }
    @Test void otherExercisesAreDeniedRatherThanReinterpretedAsCompensatorySaccade() {
        var fixation = new TherapeuticExerciseParameters.Fixation(TherapeuticExerciseParameters.FixationMode.SUSTAINED_TARGET_FIXATION,
                TherapeuticExerciseParameters.TargetPosition.CENTER, 500, 1000, 50, 5, 500, 1);
        var query = new AuthorizeDoctorCompensatorySaccadeSummaryQueryService((doctor, id) -> Optional.of(new Snapshot(id, configuration(fixation))));
        assertThrows(SecurityException.class, () -> query.authorize("synthetic-doctor", SESSION));
    }
    @Test void otherScanningModesAreDeniedInsteadOfSharingCompensatorySaccadePermission() {
        for (var mode : List.of(TherapeuticExerciseParameters.VisualScanningMode.SYSTEMATIC_EXPLORATION,
                TherapeuticExerciseParameters.VisualScanningMode.STANDARD_SCANNING)) {
            var parameters = new TherapeuticExerciseParameters.VisualScanning(mode,
                    TherapeuticExerciseParameters.AffectedHemifield.LEFT, 500, 3000, 10, 50, 5, 500, 1);
            var query = new AuthorizeDoctorCompensatorySaccadeSummaryQueryService((doctor, id) ->
                    Optional.of(new Snapshot(id, configuration(parameters))));
            assertThrows(SecurityException.class, () -> query.authorize("synthetic-doctor", SESSION));
        }
    }
    @Test void prescribedSchemaRetainsExactModalityAndFrozenPolicy() {
        for (var mode : TherapeuticExerciseParameters.VisualScanningMode.values()) {
            var scanning = new TherapeuticExerciseParameters.VisualScanning(mode,
                    TherapeuticExerciseParameters.AffectedHemifield.LEFT, 500, 3000, 10, 50, 5, 500, 1);
            var prescribed = new TherapeuticExerciseParameters.Prescribed(scanning,
                    new CompletionTarget(CompletionTarget.Criterion.REPETITIONS, 5));
            var original = new Snapshot(SESSION, configuration(prescribed));
            var calls = new AtomicInteger();
            var query = new AuthorizeDoctorCompensatorySaccadeSummaryQueryService((doctor, id) -> {
                calls.incrementAndGet(); return Optional.of(original);
            });
            if (mode == TherapeuticExerciseParameters.VisualScanningMode.COMPENSATORY_SACCADE) {
                var permit = query.authorize("synthetic-doctor", SESSION);
                assertEquals(CONFIGURATION, permit.configurationId()); assertEquals(3, permit.configurationVersion());
                assertEquals(500, permit.minimumFixationMs()); assertEquals(3000, permit.maximumStimulusDurationMs());
            } else assertThrows(SecurityException.class, () -> query.authorize("synthetic-doctor", SESSION));
            assertEquals(1, calls.get()); assertSame(prescribed, original.configuration().parameters());
        }
    }
}

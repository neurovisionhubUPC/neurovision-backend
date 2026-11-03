package com.neurovision.rehab.clinical.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.clinical.application.ports.out.authorization.VisualSearchAuthorizationSnapshotRepository;
import com.neurovision.rehab.clinical.application.ports.out.authorization.VisualSearchAuthorizationSnapshotRepository.Snapshot;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AuthorizeVisualSearchSessionQueryServiceTest {
    static final TherapySessionId SESSION = new TherapySessionId(new UUID(0, 1));
    static final UUID CONFIGURATION = new UUID(0, 2);
    static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    static final String SUBJECT = "synthetic-patient";
    static TherapeuticExerciseParameters.VisualSearch parameters() {
        return new TherapeuticExerciseParameters.VisualSearch(TherapeuticExerciseParameters.VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                TherapeuticExerciseParameters.AffectedHemifield.LEFT, 5, 2, 500, 3000, new BigDecimal("0.5"), 50, 1);
    }
    static TherapeuticConfigurationSnapshot configuration(TherapeuticExerciseParameters parameters) {
        return new TherapeuticConfigurationSnapshot(new TherapeuticConfigurationId(CONFIGURATION), 3,
                parameters.exercise(), parameters.schemaVersion(), parameters);
    }
    static Snapshot snapshot(TherapySessionStatus status, Instant lease) {
        return new Snapshot(SESSION, status, lease, configuration(parameters()));
    }
    static AuthorizeVisualSearchSessionQueryService service(VisualSearchAuthorizationSnapshotRepository repository) {
        return new AuthorizeVisualSearchSessionQueryService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void returnsOnlyFrozenConfigurationAndSeparateInsertionEligibility() {
        var query = service((subject, id) -> {
            assertEquals(SUBJECT, subject.value()); assertEquals(SESSION, id);
            return Optional.of(snapshot(TherapySessionStatus.ACTIVE, NOW.plusSeconds(1)));
        });
        var permit = query.authorize(SUBJECT, SESSION);
        assertTrue(permit.mayInsert()); assertEquals(SESSION.value(), permit.therapySessionId());
        assertEquals(CONFIGURATION, permit.configurationId()); assertEquals(3, permit.configurationVersion());
        assertEquals(500, permit.minimumFixationMs()); assertEquals(3000, permit.maximumSearchTimeMs());
        assertFalse(permit.toString().contains(SESSION.value().toString()));
    }

    @Test void expiryAndEveryNonActiveStatusPermitExistingChecksButNotNewInsertion() {
        for (var status : TherapySessionStatus.values()) {
            var query = service((s, id) -> Optional.of(snapshot(status, status == TherapySessionStatus.ACTIVE ? NOW : null)));
            assertFalse(query.authorize(SUBJECT, SESSION).mayInsert());
        }
        assertFalse(service((s, id) -> Optional.of(snapshot(TherapySessionStatus.ACTIVE, NOW.minusSeconds(1)))).authorize(SUBJECT, SESSION).mayInsert());
        assertFalse(service((s, id) -> Optional.of(snapshot(TherapySessionStatus.ACTIVE, null))).authorize(SUBJECT, SESSION).mayInsert());
    }

    @Test void missingOwnershipAndNonCanonicalSubjectsAreDeniedWithoutFallback() {
        var calls = new AtomicInteger(); var query = service((s, id) -> { calls.incrementAndGet(); return Optional.empty(); });
        for (String subject : Arrays.asList(null, "", " ", " synthetic-patient "))
            assertThrows(SecurityException.class, () -> query.authorize(subject, SESSION));
        assertThrows(SecurityException.class, () -> query.authorize(SUBJECT, null)); assertEquals(0, calls.get());
        assertThrows(SecurityException.class, () -> query.authorize(SUBJECT, SESSION)); assertEquals(1, calls.get());
    }

    @Test void dependencyFailuresAndCrossSessionSnapshotsAreUnavailableWithoutPrivateMessages() {
        for (int kind = 0; kind < 3; kind++) {
            int k = kind;
            var query = service((s, id) -> switch (k) {
                case 0 -> { throw new IllegalArgumentException("private stored configuration"); }
                case 1 -> null;
                default -> Optional.of(new Snapshot(new TherapySessionId(new UUID(0, 9)), TherapySessionStatus.ACTIVE,
                        NOW.plusSeconds(1), configuration(parameters())));
            });
            var error = assertThrows(IllegalStateException.class, () -> query.authorize(SUBJECT, SESSION));
            assertEquals("Visual Search authorization unavailable", error.getMessage()); assertNull(error.getCause());
        }
    }

    @Test void aDifferentExerciseIsDeniedAndPriorPermissionIsNeverCached() {
        var fixation = new TherapeuticExerciseParameters.Fixation(TherapeuticExerciseParameters.FixationMode.SUSTAINED_TARGET_FIXATION,
                TherapeuticExerciseParameters.TargetPosition.CENTER, 500, 1000, 50, 5, 500, 1);
        var other = new Snapshot(SESSION, TherapySessionStatus.ACTIVE, NOW.plusSeconds(1), configuration(fixation));
        assertThrows(SecurityException.class, () -> service((s, id) -> Optional.of(other)).authorize(SUBJECT, SESSION));
        var calls = new AtomicInteger();
        var query = service((s, id) -> calls.incrementAndGet() == 1
                ? Optional.of(snapshot(TherapySessionStatus.ACTIVE, NOW.plusSeconds(1))) : Optional.empty());
        assertTrue(query.authorize(SUBJECT, SESSION).mayInsert());
        assertThrows(SecurityException.class, () -> query.authorize(SUBJECT, SESSION)); assertEquals(2, calls.get());
    }
}

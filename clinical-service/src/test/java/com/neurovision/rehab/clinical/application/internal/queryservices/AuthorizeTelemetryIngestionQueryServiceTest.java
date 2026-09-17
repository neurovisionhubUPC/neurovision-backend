package com.neurovision.rehab.clinical.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.clinical.application.ports.out.authorization.TelemetryAuthorizationFacts;
import com.neurovision.rehab.clinical.application.ports.out.authorization.TelemetryAuthorizationSnapshotRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizeTelemetryIngestionQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final TherapySessionId SESSION_ID = new TherapySessionId(UUID.randomUUID());
    private TelemetryAuthorizationFacts facts =
            new TelemetryAuthorizationFacts(TherapySessionStatus.ACTIVE, NOW.plusSeconds(30));

    @Test
    void permitsOnlyOwnedActiveUnexpiredSessionWithCurrentAssignment() {
        assertEquals(SESSION_ID, service().authorize("patient-subject", SESSION_ID));
    }

    @Test
    void deniesUnknownPatientSessionWrongOwnerAndRevokedAssignmentIndistinguishably() {
        assertDenied("other-subject");
        facts = null; // The snapshot port hides absent session, owner, or current assignment alike.
        assertDenied("patient-subject");
    }

    @Test
    void deniesTerminalAndExpiredIncludingExactLeaseBoundary() {
        facts = new TelemetryAuthorizationFacts(TherapySessionStatus.COMPLETED, null);
        assertDenied("patient-subject");
        facts = new TelemetryAuthorizationFacts(TherapySessionStatus.ACTIVE, NOW);
        assertDenied("patient-subject");
        facts = new TelemetryAuthorizationFacts(TherapySessionStatus.ACTIVE, NOW.minusNanos(1));
        assertDenied("patient-subject");
    }

    @Test
    void deniesAmbiguousOrInvalidSubjectAndMissingSessionId() {
        assertDenied(null);
        assertDenied(" ");
        assertDenied(" patient-subject ");
        assertThrows(TelemetryIngestionDeniedException.class,
                () -> service().authorize("patient-subject", null));
    }

    private void assertDenied(String subject) {
        assertThrows(TelemetryIngestionDeniedException.class,
                () -> service().authorize(subject, SESSION_ID));
    }

    private AuthorizeTelemetryIngestionQueryService service() {
        TelemetryAuthorizationSnapshotRepository snapshots = (subject, sessionId) ->
                subject.equals(new CognitoSubject("patient-subject")) && sessionId.equals(SESSION_ID)
                        ? Optional.ofNullable(facts) : Optional.empty();
        return new AuthorizeTelemetryIngestionQueryService(snapshots, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}

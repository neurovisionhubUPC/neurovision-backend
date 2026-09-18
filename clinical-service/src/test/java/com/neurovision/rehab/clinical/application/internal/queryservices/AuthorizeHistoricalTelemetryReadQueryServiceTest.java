package com.neurovision.rehab.clinical.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.clinical.application.ports.out.authorization.HistoricalTelemetryReadAuthorizationPort;
import com.neurovision.rehab.clinical.application.ports.out.authorization.HistoricalTelemetryReadAuthorizationPort.ReaderRole;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizeHistoricalTelemetryReadQueryServiceTest {
    private final TherapySessionId sessionId = new TherapySessionId(UUID.randomUUID());
    private boolean permitted = true;
    private ReaderRole observedRole;

    @Test
    void passesPatientAndDoctorRoleToRepository() {
        var service = service();
        assertEquals(sessionId, service.authorize("subject", sessionId, ReaderRole.PATIENT));
        assertEquals(ReaderRole.PATIENT, observedRole);
        assertEquals(sessionId, service.authorize("subject", sessionId, ReaderRole.DOCTOR));
        assertEquals(ReaderRole.DOCTOR, observedRole);
    }

    @Test
    void deniesMissingUnknownAndAmbiguousIdentity() {
        var service = service();
        assertThrows(HistoricalTelemetryReadDeniedException.class,
                () -> service.authorize(null, sessionId, ReaderRole.PATIENT));
        assertThrows(HistoricalTelemetryReadDeniedException.class,
                () -> service.authorize(" subject ", sessionId, ReaderRole.PATIENT));
        assertThrows(HistoricalTelemetryReadDeniedException.class,
                () -> service.authorize("subject", null, ReaderRole.PATIENT));
        assertThrows(HistoricalTelemetryReadDeniedException.class,
                () -> service.authorize("subject", sessionId, null));
        permitted = false;
        assertThrows(HistoricalTelemetryReadDeniedException.class,
                () -> service.authorize("subject", sessionId, ReaderRole.DOCTOR));
    }

    private AuthorizeHistoricalTelemetryReadQueryService service() {
        HistoricalTelemetryReadAuthorizationPort port = (subject, session, role) -> {
            observedRole = role;
            return permitted && sessionId.equals(session) && "subject".equals(subject.value());
        };
        return new AuthorizeHistoricalTelemetryReadQueryService(port);
    }
}

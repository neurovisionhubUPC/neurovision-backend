package com.neurovision.rehab.telemetry.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryReplayPort;
import org.junit.jupiter.api.Test;

class TelemetryReplayCommandServiceTest {
    @Test
    void exactConfirmationAndAllowlistedIdentityAndReasonAreRequired() {
        var port = new StubPort();
        var service = new TelemetryReplayCommandService(port);
        var position = new TelemetryReplayPort.Position(1, 42);
        assertThrows(IllegalArgumentException.class,
                () -> service.replay(position, "operator", "RETRY_AFTER_RECOVERY", "REPLAY 1:42"));
        assertThrows(IllegalArgumentException.class,
                () -> service.replay(position, "patient 123", "RETRY_AFTER_RECOVERY",
                        "REPLAY telemetry-batch-accepted.v1.dlq:1:42"));
        assertThrows(IllegalArgumentException.class,
                () -> service.replay(position, "operator", "free text with medical data",
                        "REPLAY telemetry-batch-accepted.v1.dlq:1:42"));
        assertEquals(0, port.calls);
        service.replay(position, "operator", "RETRY_AFTER_RECOVERY",
                "REPLAY telemetry-batch-accepted.v1.dlq:1:42");
        assertEquals(1, port.calls);
    }

    private static class StubPort implements TelemetryReplayPort {
        int calls;
        @Override public Metadata inspect(Position position) { throw new UnsupportedOperationException(); }
        @Override public ReplayReceipt replay(Position position, String operator, String reasonCode) {
            calls++;
            return new ReplayReceipt("test", 0, 0);
        }
    }
}

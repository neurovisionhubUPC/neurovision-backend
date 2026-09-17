package com.neurovision.rehab.telemetry.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryDeadLetterPort.FailedRecord;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryDeadLetterPort.FailureCode;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class KafkaTelemetryDeadLetterPublisherTest {
    @Test
    void unavailableBrokerFailsWithinBoundRatherThanClaimingQuarantine() {
        var publisher = new KafkaTelemetryDeadLetterPublisher("127.0.0.1:65533",
                "telemetry-batch-accepted.v1.dlq", 1000);
        try {
            var failed = new FailedRecord("pseudonymous-key", "private-medical-payload",
                    FailureCode.INVALID_EVENT, "telemetry-batch-accepted.v1", 0, 1);
            assertThrows(Exception.class, () -> publisher.quarantine(failed).toCompletableFuture()
                    .get(2, TimeUnit.SECONDS));
        } finally {
            publisher.close();
        }
    }
}

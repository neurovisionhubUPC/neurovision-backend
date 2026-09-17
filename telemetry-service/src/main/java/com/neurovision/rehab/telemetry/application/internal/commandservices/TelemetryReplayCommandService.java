package com.neurovision.rehab.telemetry.application.internal.commandservices;

import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryReplayPort;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryReplayPort.Position;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryReplayPort.ReplayReceipt;
import java.util.Set;

/** Validates deliberate local operator intent before the Kafka adapter can publish. */
public final class TelemetryReplayCommandService {
    private static final Set<String> REASONS = Set.of(
            "RETRY_AFTER_RECOVERY", "RETRY_AFTER_CAPACITY_RESTORED", "RETRY_AFTER_OPERATOR_REVIEW");
    private final TelemetryReplayPort port;

    public TelemetryReplayCommandService(TelemetryReplayPort port) {
        this.port = port;
    }

    public TelemetryReplayPort.Metadata inspect(Position position) {
        return port.inspect(position);
    }

    public ReplayReceipt replay(Position position, String operator, String reasonCode, String confirmation) {
        if (operator == null || !operator.matches("[A-Za-z0-9._@-]{3,64}")) {
            throw new IllegalArgumentException("Operator identifier must be 3-64 safe characters");
        }
        if (!REASONS.contains(reasonCode)) {
            throw new IllegalArgumentException("Unsupported replay reason code");
        }
        String expected = "REPLAY telemetry-batch-accepted.v1.dlq:" + position.partition() + ":" + position.offset();
        if (!expected.equals(confirmation)) {
            throw new IllegalArgumentException("Exact DLQ position confirmation required");
        }
        return port.replay(position, operator, reasonCode);
    }
}

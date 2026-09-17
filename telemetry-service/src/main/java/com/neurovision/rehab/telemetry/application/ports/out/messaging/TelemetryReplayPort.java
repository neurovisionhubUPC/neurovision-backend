package com.neurovision.rehab.telemetry.application.ports.out.messaging;

/** Operator-only access to one exact DLQ record. No payload leaves this port. */
public interface TelemetryReplayPort {
    record Position(int partition, long offset) {
        public Position {
            if (partition < 0 || offset < 0) throw new IllegalArgumentException("Invalid DLQ position");
        }
    }

    record Metadata(Position position, TelemetryDeadLetterPort.FailureCode failureCode, int valueBytes,
                    String sourceTopic, int sourcePartition, long sourceOffset) {}

    record ReplayReceipt(String replayId, int targetPartition, long targetOffset) {}

    Metadata inspect(Position position);

    ReplayReceipt replay(Position position, String operator, String reasonCode);
}

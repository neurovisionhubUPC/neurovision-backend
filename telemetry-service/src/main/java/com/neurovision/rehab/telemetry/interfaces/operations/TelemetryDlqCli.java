package com.neurovision.rehab.telemetry.interfaces.operations;

import com.neurovision.rehab.telemetry.application.internal.commandservices.TelemetryReplayCommandService;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryReplayPort.Position;
import com.neurovision.rehab.telemetry.infrastructure.messaging.KafkaTelemetryReplayAdapter;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Local-only exact-position operator entry point; never prints a Kafka key or value. */
public final class TelemetryDlqCli {
    private TelemetryDlqCli() {}

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err);
        if (exit != 0) System.exit(exit);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            Map<String, String> options = parse(args);
            String action = require(options, "action");
            int partition = Integer.parseInt(require(options, "partition"));
            long offset = Long.parseLong(require(options, "offset"));
            var position = new Position(partition, offset);
            var service = new TelemetryReplayCommandService(
                    new KafkaTelemetryReplayAdapter(require(options, "bootstrap")));
            if ("inspect".equals(action)) {
                if (!options.keySet().equals(Set.of("action", "partition", "offset", "bootstrap"))) {
                    throw new IllegalArgumentException("Unexpected inspect argument");
                }
                var metadata = service.inspect(position);
                out.printf("DLQ partition=%d offset=%d failure=%s bytes=%d sourcePartition=%d sourceOffset=%d%n",
                        position.partition(), position.offset(), metadata.failureCode(), metadata.valueBytes(),
                        metadata.sourcePartition(), metadata.sourceOffset());
            } else if ("replay".equals(action)) {
                if (!options.keySet().equals(Set.of("action", "partition", "offset", "bootstrap",
                        "operator", "reason", "confirm"))) {
                    throw new IllegalArgumentException("Unexpected replay argument");
                }
                var receipt = service.replay(position, require(options, "operator"),
                        require(options, "reason"), require(options, "confirm"));
                out.printf("Replay audit=%s targetPartition=%d targetOffset=%d%n",
                        receipt.replayId(), receipt.targetPartition(), receipt.targetOffset());
            } else {
                throw new IllegalArgumentException("Action must be inspect or replay");
            }
            return 0;
        } catch (RuntimeException rejected) {
            // Never render an external exception: Kafka and database errors may contain sensitive values.
            err.println("Telemetry DLQ operation rejected or unavailable; consult the restricted audit and runbook.");
            return 2;
        }
    }

    private static Map<String, String> parse(String[] args) {
        var allowed = Set.of("action", "bootstrap", "partition", "offset", "operator", "reason", "confirm");
        if (args.length % 2 != 0) throw new IllegalArgumentException("Key/value arguments required");
        var values = new HashMap<String, String>();
        for (int index = 0; index < args.length; index += 2) {
            String key = args[index];
            if (!key.startsWith("--") || !allowed.contains(key.substring(2))
                    || values.putIfAbsent(key.substring(2), args[index + 1]) != null) {
                throw new IllegalArgumentException("Invalid or duplicate argument");
            }
        }
        return values;
    }

    private static String require(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required argument");
        return value;
    }
}

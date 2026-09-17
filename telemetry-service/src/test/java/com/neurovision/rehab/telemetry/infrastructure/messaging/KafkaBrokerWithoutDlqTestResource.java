package com.neurovision.rehab.telemetry.infrastructure.messaging;

import java.util.HashMap;
import java.util.Map;

/** Test-only broker with auto-creation disabled and no DLQ destination. */
public class KafkaBrokerWithoutDlqTestResource extends KafkaBrokerTestResource {
    @Override
    public Map<String, String> start() {
        var config = new HashMap<>(super.start());
        config.put("mp.messaging.incoming.telemetry-batch-persist.enabled", "true");
        return config;
    }

    @Override
    protected boolean provisionDlq() {
        return false;
    }
}

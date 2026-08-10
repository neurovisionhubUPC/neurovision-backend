package com.neurovision.rehab.iam.infrastructure.messaging.outbox;

public interface OutboxEventPublisher {

    void publish(ClaimedOutboxEvent event);
}

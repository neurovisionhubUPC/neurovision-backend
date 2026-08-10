package com.neurovision.rehab.iam.infrastructure.messaging.kafka.publisher;

import com.neurovision.rehab.iam.infrastructure.messaging.outbox.ClaimedOutboxEvent;
import com.neurovision.rehab.iam.infrastructure.messaging.outbox.OutboxEventPublisher;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

@ApplicationScoped
public class KafkaOutboxEventPublisher implements OutboxEventPublisher {

    private final Emitter<Record<String, String>> emitter;

    @Inject
    public KafkaOutboxEventPublisher(
            @Channel("patient-account-invited") Emitter<Record<String, String>> emitter) {
        this.emitter = emitter;
    }

    @Override
    public void publish(ClaimedOutboxEvent event) {
        if (!"PatientAccountInvited".equals(event.eventType()) || event.eventVersion() != 1) {
            throw new IllegalArgumentException(
                    "Unsupported outbox event: %s v%s".formatted(event.eventType(), event.eventVersion()));
        }
        var record = Record.of(event.aggregateId().toString(), event.payload().toString());
        emitter.send(record).toCompletableFuture().join();
    }
}

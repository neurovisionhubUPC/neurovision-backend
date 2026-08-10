package com.neurovision.rehab.iam.infrastructure.messaging.kafka.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.iam.infrastructure.messaging.outbox.ClaimedOutboxEvent;
import io.smallrye.reactive.messaging.kafka.Record;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KafkaOutboxEventPublisherTest {

    @SuppressWarnings("unchecked")
    @Test
    void shouldPublishPayloadUsingAggregateIdAsKafkaKey() {
        Emitter<Record<String, String>> emitter = mock(Emitter.class);
        when(emitter.send(any(Record.class))).thenReturn(CompletableFuture.completedFuture(null));
        var publisher = new KafkaOutboxEventPublisher(emitter);
        var payload = new ObjectMapper().createObjectNode()
                .put("eventId", "11111111-1111-1111-1111-111111111111")
                .put("patientSubject", "patient-sub");
        var event = new ClaimedOutboxEvent(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "PatientAccountInvited",
                (short) 1,
                payload,
                Instant.parse("2026-08-10T15:00:00Z"),
                1);

        publisher.publish(event);

        var record = ArgumentCaptor.forClass(Record.class);
        verify(emitter).send(record.capture());
        assertEquals(event.aggregateId().toString(), record.getValue().key());
        assertEquals(payload.toString(), record.getValue().value());
    }
}

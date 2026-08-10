package com.neurovision.rehab.iam.infrastructure.messaging.outbox;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

    @Test
    void shouldPublishClaimedEventsAndRecordIndividualFailures() {
        var now = Instant.parse("2026-08-10T15:00:00Z");
        var store = mock(OutboxEventStore.class);
        var publisher = mock(OutboxEventPublisher.class);
        var first = event("11111111-1111-1111-1111-111111111111");
        var second = event("22222222-2222-2222-2222-222222222222");
        when(store.claimPending("worker-1", 25, now, now.plusSeconds(60)))
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("Kafka unavailable")).when(publisher).publish(second);
        var relay = new OutboxRelay(
                store,
                publisher,
                Clock.fixed(now, ZoneOffset.UTC),
                25,
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                "worker-1");

        relay.publishPending();

        verify(publisher).publish(first);
        verify(store).markPublished(first.id(), "worker-1", now);
        verify(publisher).publish(second);
        verify(store).recordFailure(
                second.id(), "worker-1", "Kafka unavailable", now.plusSeconds(5));
    }

    private static ClaimedOutboxEvent event(String id) {
        var objectMapper = new ObjectMapper();
        return new ClaimedOutboxEvent(
                UUID.fromString(id),
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "PatientAccountInvited",
                (short) 1,
                objectMapper.createObjectNode().put("eventId", id),
                Instant.parse("2026-08-10T14:59:00Z"),
                1);
    }
}

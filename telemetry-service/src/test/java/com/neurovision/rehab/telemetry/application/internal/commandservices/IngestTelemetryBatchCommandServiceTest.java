package com.neurovision.rehab.telemetry.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.neurovision.rehab.telemetry.application.internal.validation.TelemetryValidationPolicy;
import com.neurovision.rehab.telemetry.application.ports.out.authorization.TherapySessionAuthorizationPort;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryPublicationPort;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IngestTelemetryBatchCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:06Z");
    private static final String TOKEN = "secret-original-token";
    private final TelemetryBatch batch = new TelemetryBatch(1, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), 0, NOW.minusSeconds(6), NOW.minusSeconds(1),
            List.of(new TelemetryBatch.GazeSample(0, .25, .75, .9)),
            List.of(new TelemetryBatch.GameplaySample(5, .2, .8, .6, .4, 10, 1.5, .5)));

    @Test
    void authorizedValidBatchReachesPublicationExactlyOnceWithoutCredential() {
        var auth = new FakeAuthorization(TherapySessionAuthorizationPort.Decision.PERMITTED);
        var publisher = new FakePublisher(true);
        var outcome = service(auth, publisher).ingest(batch, TOKEN, "trace-id");
        assertEquals(IngestTelemetryBatchCommandService.Status.ACCEPTED, outcome.status());
        assertEquals(1, auth.calls);
        assertSame(batch.therapySessionId(), auth.sessionId);
        assertEquals(TOKEN, auth.token);
        assertEquals(1, publisher.calls);
        assertSame(batch, publisher.accepted.validation().batch());
        assertEquals(NOW, publisher.accepted.validation().serverReceivedAt());
        assertEquals("trace-id", publisher.accepted.traceId());
        org.junit.jupiter.api.Assertions.assertNotNull(publisher.accepted.correlationId());
        assertFalse(publisher.accepted.toString().contains(TOKEN));
    }

    @Test
    void deniedInvalidAndUnavailableAuthorizationNeverPublish() {
        for (var decision : List.of(TherapySessionAuthorizationPort.Decision.DENIED,
                TherapySessionAuthorizationPort.Decision.INVALID_TOKEN,
                TherapySessionAuthorizationPort.Decision.UNAVAILABLE)) {
            var publisher = new FakePublisher(true);
            var outcome = service(new FakeAuthorization(decision), publisher).ingest(batch, TOKEN, null);
            assertEquals(0, publisher.calls);
            assertEquals(switch (decision) {
                case DENIED -> IngestTelemetryBatchCommandService.Status.FORBIDDEN;
                case INVALID_TOKEN -> IngestTelemetryBatchCommandService.Status.UNAUTHENTICATED;
                default -> IngestTelemetryBatchCommandService.Status.UNAVAILABLE;
            }, outcome.status());
        }
    }

    @Test
    void invalidBatchAndMissingTokenNeverReachPublication() {
        var auth = new FakeAuthorization(TherapySessionAuthorizationPort.Decision.PERMITTED);
        var publisher = new FakePublisher(true);
        assertEquals(IngestTelemetryBatchCommandService.Status.UNAUTHENTICATED,
                service(auth, publisher).ingest(batch, " ", null).status());
        var malformed = new TelemetryBatch(1, batch.batchId(), batch.therapySessionId(),
                batch.streamId(), batch.sequence(), batch.capturedAt(), batch.sentAt(),
                List.of(new TelemetryBatch.GazeSample(0, 2, .75, .9)), List.of());
        assertEquals(IngestTelemetryBatchCommandService.Status.INVALID_BATCH,
                service(auth, publisher).ingest(malformed, TOKEN, null).status());
        assertEquals(0, auth.calls);
        assertEquals(0, publisher.calls);
    }

    @Test
    void missingBrokerAcknowledgmentFailsClosedForRetries() {
        var publisher = new FakePublisher(false);
        var service = service(new FakeAuthorization(TherapySessionAuthorizationPort.Decision.PERMITTED), publisher);
        assertEquals(IngestTelemetryBatchCommandService.Status.UNAVAILABLE, service.ingest(batch, TOKEN, null).status());
        assertEquals(IngestTelemetryBatchCommandService.Status.UNAVAILABLE, service.ingest(batch, TOKEN, null).status());
        assertEquals(2, publisher.calls);
    }

    @Test
    void measuredPolicyEnforcesSampleAndPerInstanceRateBounds() {
        var auth = new FakeAuthorization(TherapySessionAuthorizationPort.Decision.PERMITTED);
        var publisher = new FakePublisher(false);
        var sampleBound = new IngestTelemetryBatchCommandService(auth, publisher,
                new TelemetryValidationPolicy(1, Duration.ZERO), 2, 10,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(IngestTelemetryBatchCommandService.Status.INVALID_BATCH,
                sampleBound.ingest(batch, TOKEN, null).status());
        assertEquals(0, auth.calls);

        var rateBound = new IngestTelemetryBatchCommandService(auth, publisher,
                new TelemetryValidationPolicy(2, Duration.ZERO), 2, 1,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(IngestTelemetryBatchCommandService.Status.UNAVAILABLE,
                rateBound.ingest(batch, TOKEN, null).status());
        assertEquals(IngestTelemetryBatchCommandService.Status.RATE_LIMITED,
                rateBound.ingest(batch, TOKEN, null).status());
        assertEquals(1, auth.calls);
        assertEquals(1, publisher.calls);
    }

    @Test
    void adapterFailuresCannotEscapeAsSuccessfulIngestion() {
        var policy = new TelemetryValidationPolicy(2, Duration.ZERO);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var authFailure = new IngestTelemetryBatchCommandService((id, token) -> {
            throw new IllegalStateException("Clinical unavailable");
        }, accepted -> true, policy, 2, 10, clock);
        assertEquals(IngestTelemetryBatchCommandService.Status.UNAVAILABLE,
                authFailure.ingest(batch, TOKEN, null).status());
        var publicationFailure = new IngestTelemetryBatchCommandService((id, token) ->
                TherapySessionAuthorizationPort.Decision.PERMITTED, accepted -> {
                    throw new IllegalStateException("Kafka unavailable");
                }, policy, 2, 10, clock);
        assertEquals(IngestTelemetryBatchCommandService.Status.UNAVAILABLE,
                publicationFailure.ingest(batch, TOKEN, null).status());
    }

    private IngestTelemetryBatchCommandService service(FakeAuthorization auth, FakePublisher publisher) {
        return new IngestTelemetryBatchCommandService(auth, publisher,
                new TelemetryValidationPolicy(2, Duration.ZERO), 2, 10,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class FakeAuthorization implements TherapySessionAuthorizationPort {
        private final Decision decision;
        private int calls;
        private UUID sessionId;
        private String token;

        private FakeAuthorization(Decision decision) { this.decision = decision; }

        @Override
        public Decision authorize(UUID sessionId, String token) {
            this.calls++;
            this.sessionId = sessionId;
            this.token = token;
            return decision;
        }
    }

    private static final class FakePublisher implements TelemetryPublicationPort {
        private final boolean acknowledged;
        private int calls;
        private TelemetryPublicationPort.Publication accepted;

        private FakePublisher(boolean acknowledged) { this.acknowledged = acknowledged; }

        @Override
        public boolean publish(TelemetryPublicationPort.Publication accepted) {
            calls++;
            this.accepted = accepted;
            return acknowledged;
        }
    }
}

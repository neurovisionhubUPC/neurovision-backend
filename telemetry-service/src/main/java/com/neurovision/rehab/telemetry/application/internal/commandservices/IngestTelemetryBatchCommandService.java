package com.neurovision.rehab.telemetry.application.internal.commandservices;

import com.neurovision.rehab.telemetry.application.internal.validation.TelemetryBatchValidator;
import com.neurovision.rehab.telemetry.application.internal.validation.TelemetryRejectionCategory;
import com.neurovision.rehab.telemetry.application.internal.validation.TelemetryValidationPolicy;
import com.neurovision.rehab.telemetry.application.ports.out.authorization.TherapySessionAuthorizationPort;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryPublicationPort;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryPublicationPort.Publication;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class IngestTelemetryBatchCommandService {
    private final TherapySessionAuthorizationPort authorization;
    private final TelemetryPublicationPort publication;
    private final TelemetryBatchValidator validator = new TelemetryBatchValidator();
    private final TelemetryValidationPolicy policy;
    private final Semaphore concurrentRequests;
    private final int maximumBatchesPerSecond;
    private final AtomicLong rateWindow = new AtomicLong();
    private final AtomicLong rateCount = new AtomicLong();
    private final Clock clock;

    @Inject
    public IngestTelemetryBatchCommandService(TherapySessionAuthorizationPort authorization,
            TelemetryPublicationPort publication,
            @ConfigProperty(name = "telemetry.ingress.max-samples") int maximumSamples,
            @ConfigProperty(name = "telemetry.ingress.max-future-skew-seconds") long maximumSkewSeconds,
            @ConfigProperty(name = "telemetry.ingress.max-concurrent") int maximumConcurrent,
            @ConfigProperty(name = "telemetry.ingress.max-batches-per-second") int maximumBatchesPerSecond) {
        this(authorization, publication, new TelemetryValidationPolicy(maximumSamples,
                Duration.ofSeconds(maximumSkewSeconds)), maximumConcurrent, maximumBatchesPerSecond,
                Clock.systemUTC());
    }

    public IngestTelemetryBatchCommandService(TherapySessionAuthorizationPort authorization,
            TelemetryPublicationPort publication, TelemetryValidationPolicy policy,
            int maximumConcurrent, int maximumBatchesPerSecond, Clock clock) {
        this.authorization = Objects.requireNonNull(authorization);
        this.publication = Objects.requireNonNull(publication);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        if (maximumConcurrent <= 0 || maximumBatchesPerSecond <= 0) {
            throw new IllegalArgumentException("Ingress bounds must be positive");
        }
        this.concurrentRequests = new Semaphore(maximumConcurrent);
        this.maximumBatchesPerSecond = maximumBatchesPerSecond;
    }

    public Outcome ingest(TelemetryBatch batch, String originalAccessToken, String traceId) {
        if (originalAccessToken == null || originalAccessToken.isBlank()) {
            return new Outcome(Status.UNAUTHENTICATED, null);
        }
        if (!concurrentRequests.tryAcquire()) {
            return new Outcome(Status.UNAVAILABLE, null);
        }
        try {
            if (!withinRateLimit(clock.instant())) {
                return new Outcome(Status.RATE_LIMITED, null);
            }
            Instant receivedAt = clock.instant();
            var validated = validator.validate(batch, policy, receivedAt);
            if (!validated.isAccepted()) {
                return new Outcome(Status.INVALID_BATCH, validated.rejectionCategory());
            }
            final TherapySessionAuthorizationPort.Decision decision;
            try {
                decision = authorization.authorize(batch.therapySessionId(), originalAccessToken);
            } catch (RuntimeException unavailableClinical) {
                return new Outcome(Status.UNAVAILABLE, null);
            }
            if (decision == TherapySessionAuthorizationPort.Decision.DENIED) {
                return new Outcome(Status.FORBIDDEN, null);
            }
            if (decision == TherapySessionAuthorizationPort.Decision.INVALID_TOKEN) {
                return new Outcome(Status.UNAUTHENTICATED, null);
            }
            if (decision != TherapySessionAuthorizationPort.Decision.PERMITTED) {
                return new Outcome(Status.UNAVAILABLE, null);
            }
            try {
                return publication.publish(new Publication(validated, UUID.randomUUID(), traceId))
                        ? new Outcome(Status.ACCEPTED, null)
                        : new Outcome(Status.UNAVAILABLE, null);
            } catch (RuntimeException unavailablePublisher) {
                return new Outcome(Status.UNAVAILABLE, null);
            }
        } finally {
            concurrentRequests.release();
        }
    }

    private synchronized boolean withinRateLimit(Instant now) {
        long second = now.getEpochSecond();
        if (rateWindow.get() != second) {
            rateWindow.set(second);
            rateCount.set(0);
        }
        return rateCount.incrementAndGet() <= maximumBatchesPerSecond;
    }

    public enum Status { ACCEPTED, INVALID_BATCH, UNAUTHENTICATED, FORBIDDEN, UNAVAILABLE, RATE_LIMITED }
    public record Outcome(Status status, TelemetryRejectionCategory rejectionCategory) {}
}

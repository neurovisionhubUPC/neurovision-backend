package com.neurovision.rehab.iam.infrastructure.messaging.outbox;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OutboxRelay {

    private static final Logger LOG = Logger.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 2_000;

    private final OutboxEventStore store;
    private final OutboxEventPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final Duration lockDuration;
    private final Duration retryDelay;
    private final String owner;

    @Inject
    public OutboxRelay(
            OutboxEventStore store,
            OutboxEventPublisher publisher,
            @ConfigProperty(name = "neurovision.outbox.batch-size", defaultValue = "25") int batchSize,
            @ConfigProperty(name = "neurovision.outbox.lock-duration", defaultValue = "60s")
                    Duration lockDuration,
            @ConfigProperty(name = "neurovision.outbox.retry-delay", defaultValue = "5s")
                    Duration retryDelay,
            @ConfigProperty(name = "neurovision.outbox.worker-id", defaultValue = "local") String workerId) {
        this(
                store,
                publisher,
                Clock.systemUTC(),
                batchSize,
                lockDuration,
                retryDelay,
                workerId + "-" + UUID.randomUUID());
    }

    OutboxRelay(
            OutboxEventStore store,
            OutboxEventPublisher publisher,
            Clock clock,
            int batchSize,
            Duration lockDuration,
            Duration retryDelay,
            String owner) {
        this.store = Objects.requireNonNull(store);
        this.publisher = Objects.requireNonNull(publisher);
        this.clock = Objects.requireNonNull(clock);
        if (batchSize < 1) {
            throw new IllegalArgumentException("Outbox batch size must be positive");
        }
        if (lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("Outbox lock duration must be positive");
        }
        if (retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("Outbox retry delay must be positive");
        }
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("Outbox owner must contain 1 to 128 characters");
        }
        this.batchSize = batchSize;
        this.lockDuration = lockDuration;
        this.retryDelay = retryDelay;
        this.owner = owner;
    }

    @Scheduled(
            every = "${neurovision.outbox.poll-interval:1s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void publishPending() {
        Instant now = clock.instant();
        var claimed = store.claimPending(owner, batchSize, now, now.plus(lockDuration));
        for (var event : claimed) {
            publishOne(event);
        }
    }

    private void publishOne(ClaimedOutboxEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException exception) {
            String error = describe(exception);
            try {
                store.recordFailure(
                        event.id(), owner, error, clock.instant().plus(retryDelay));
            } catch (RuntimeException persistenceFailure) {
                LOG.errorf(
                        persistenceFailure,
                        "Could not record outbox failure for event %s owned by %s",
                        event.id(),
                        owner);
            }
            return;
        }
        store.markPublished(event.id(), owner, clock.instant());
    }

    private static String describe(RuntimeException exception) {
        String description = exception.getMessage();
        if (description == null || description.isBlank()) {
            description = exception.getClass().getSimpleName();
        }
        return description.length() <= MAX_ERROR_LENGTH
                ? description
                : description.substring(0, MAX_ERROR_LENGTH);
    }
}

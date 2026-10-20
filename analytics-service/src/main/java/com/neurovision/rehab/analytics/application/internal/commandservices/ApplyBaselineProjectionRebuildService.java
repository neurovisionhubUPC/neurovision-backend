package com.neurovision.rehab.analytics.application.internal.commandservices;

import com.neurovision.rehab.analytics.application.internal.queryservices.PreviewBaselineProjectionRebuildService;
import com.neurovision.rehab.analytics.application.internal.queryservices.PreviewBaselineProjectionRebuildService.PreparedProjection;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ConditionalProjectionRebuildWritePort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ConditionalProjectionRebuildWritePort.WriteRequest;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ConditionalProjectionRebuildWritePort.WriteResult;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ProjectionRebuildObservationPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Internal, unwired, bounded apply for a missing or stale baseline-v1 projection.
 * The trusted caller must obtain explicit maintenance-write permission before passing true;
 * this boolean is a fail-closed input, NOT authentication or a replacement for authorization.
 * Each subscription freshly prepares, then invokes the writer at most once within the SAME
 * five-second budget. No transaction/snapshot, source-at-write currency, or ABA guarantee.
 * A post-dispatch failure remains unknown; never automatically retry or report no mutation.
 */
public final class ApplyBaselineProjectionRebuildService {
    private final PreviewBaselineProjectionRebuildService preview;
    private final ConditionalProjectionRebuildWritePort writes;

    public ApplyBaselineProjectionRebuildService(ProjectionRebuildObservationPort observations,
            ConditionalProjectionRebuildWritePort writes) {
        this(observations, writes, new BaselineTelemetryMetricsCalculator()::calculate, System::nanoTime);
    }

    public ApplyBaselineProjectionRebuildService(ProjectionRebuildObservationPort observations,
            ConditionalProjectionRebuildWritePort writes,
            Function<List<AdmittedTelemetryBatch>, BaselineStreamSummary> calculate, LongSupplier nanoTime) {
        this.preview = new PreviewBaselineProjectionRebuildService(observations, calculate, nanoTime);
        this.writes = Objects.requireNonNull(writes);
    }

    public Uni<Outcome> apply(UUID sessionId, UUID streamId, String version, boolean writeAuthorized) {
        if (!writeAuthorized) return Uni.createFrom().item(new Outcome(Status.NOT_AUTHORIZED, null));
        return preview.withPreparedProjection(sessionId, streamId, version, this::applyPrepared);
    }

    private Uni<Outcome> applyPrepared(PreparedProjection prepared) {
        var observation = prepared.outcome();
        if (observation.status() == PreviewBaselineProjectionRebuildService.Status.CONSISTENT) {
            return Uni.createFrom().item(new Outcome(Status.NO_CHANGE, observation));
        }
        if (observation.status() != PreviewBaselineProjectionRebuildService.Status.PROJECTION_MISSING
                && observation.status() != PreviewBaselineProjectionRebuildService.Status.PROJECTION_STALE) {
            return Uni.createFrom().item(new Outcome(Status.REFUSED, observation));
        }
        final Duration timeout;
        final long startingRemaining;
        final WriteRequest request;
        try {
            request = new WriteRequest(prepared.candidate(), prepared.expected());
            startingRemaining = prepared.remainingBudgetNanos();
            timeout = Duration.ofNanos(Math.min(Duration.ofSeconds(2).toNanos(), startingRemaining));
        } catch (RuntimeException beforeDispatch) {
            var expired = new PreviewBaselineProjectionRebuildService.Outcome(
                    PreviewBaselineProjectionRebuildService.Status.DEADLINE_EXCEEDED, null, null, null);
            return Uni.createFrom().item(new Outcome(Status.REFUSED, expired));
        }
        // From invocation onwards even an immediate throw/null/lost acknowledgement is uncertain.
        try {
            var result = writes.writeIfUnchanged(request, timeout);
            if (result == null) return unknown(observation);
            var wait = remainingCall(prepared, startingRemaining, timeout);
            return result.ifNoItem().after(wait).fail()
                    .map(ack -> {
                        remainingCall(prepared, startingRemaining, timeout);
                        if (ack == WriteResult.PRECONDITION_FAILED) return new Outcome(Status.CONFLICT, observation);
                        boolean valid = request.expected() == null ? ack == WriteResult.INSERTED : ack == WriteResult.REPLACED;
                        return new Outcome(valid ? Status.APPLIED : Status.WRITE_OUTCOME_UNKNOWN, observation);
                    }).onFailure().recoverWithItem(new Outcome(Status.WRITE_OUTCOME_UNKNOWN, observation));
        } catch (RuntimeException afterDispatch) {
            return unknown(observation);
        }
    }

    private Duration remainingCall(PreparedProjection prepared, long startingRemaining, Duration timeout) {
        long remaining = prepared.remainingBudgetNanos();
        long elapsed = startingRemaining - remaining;
        long callRemaining = timeout.toNanos() - elapsed;
        if (elapsed < 0 || callRemaining <= 0) throw new IllegalStateException("Write deadline exceeded");
        return Duration.ofNanos(Math.min(remaining, callRemaining));
    }

    private Uni<Outcome> unknown(PreviewBaselineProjectionRebuildService.Outcome observation) {
        return Uni.createFrom().item(new Outcome(Status.WRITE_OUTCOME_UNKNOWN, observation));
    }

    public enum Status {
        APPLIED, NO_CHANGE, CONFLICT, REFUSED, NOT_AUTHORIZED, WRITE_OUTCOME_UNKNOWN;
        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    /** Safe observations describe the pre-write check, never a claim of post-write currency. */
    public record Outcome(Status status, PreviewBaselineProjectionRebuildService.Outcome observation) {
        public String calculationVersion() { return "baseline-v1"; }
        public String consistencyMode() { return "optimistic_repeated_read"; }
    }
}

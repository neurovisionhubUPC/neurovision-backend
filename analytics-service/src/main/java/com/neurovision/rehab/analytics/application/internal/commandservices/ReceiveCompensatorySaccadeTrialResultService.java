package com.neurovision.rehab.analytics.application.internal.commandservices;

import com.neurovision.rehab.analytics.application.ports.out.authorization.CompensatorySaccadeTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.CompensatorySaccadeTrialAuthorizationPort.Permission;
import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultStore;
import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultStore.Stored;
import com.neurovision.rehab.analytics.domain.model.valueobjects.CompensatorySaccadeTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.CompensatorySaccadeTrialResult;
import io.smallrye.mutiny.Uni;
import java.util.Objects;

/** Unwired orchestration: fresh authorization per subscription, one read and at most one insert attempt. */
public final class ReceiveCompensatorySaccadeTrialResultService {
    private final CompensatorySaccadeTrialAuthorizationPort authorization;
    private final CompensatorySaccadeTrialResultStore store;

    public ReceiveCompensatorySaccadeTrialResultService(CompensatorySaccadeTrialAuthorizationPort authorization, CompensatorySaccadeTrialResultStore store) {
        this.authorization = Objects.requireNonNull(authorization); this.store = Objects.requireNonNull(store);
    }

    public Uni<Status> receive(CompensatorySaccadeTrialResult result, String originalPatientAccessToken) {
        return Uni.createFrom().deferred(() -> {
            if (originalPatientAccessToken == null || originalPatientAccessToken.isBlank()) return item(Status.INVALID_TOKEN);
            if (result == null) return item(Status.INVALID_RESULT);
            final CompensatorySaccadeTrialAuthorizationPort.Decision decision;
            try { decision = authorization.authorize(result.therapySessionId(), originalPatientAccessToken); }
            catch (RuntimeException unavailable) { return item(Status.UNAVAILABLE); }
            if (decision == null) return item(Status.UNAVAILABLE);
            if (decision.permission() != Permission.PERMITTED) return item(switch (decision.permission()) {
                case INVALID_TOKEN -> Status.INVALID_TOKEN;
                case DENIED -> Status.DENIED;
                default -> Status.UNAVAILABLE;
            });
            var context = decision.context();
            if (!context.therapySessionId().equals(result.therapySessionId())) return item(Status.UNAVAILABLE);
            try { context.validate(result); }
            catch (RuntimeException invalid) { return item(Status.INVALID_RESULT); }
            try {
                var read = store.find(result.therapySessionId(), result.trialId());
                if (read == null) return item(Status.UNAVAILABLE);
                return read.chain(found -> {
                    if (found == null) return item(Status.UNAVAILABLE);
                    if (found.isPresent()) return item(existing(found.get(), context, result, Status.UNAVAILABLE));
                    if (!decision.mayInsert()) return item(Status.DENIED);
                    return insert(new Stored(context, result));
                }).onFailure().recoverWithItem(Status.UNAVAILABLE);
            } catch (RuntimeException unavailable) { return item(Status.UNAVAILABLE); }
        });
    }

    private Uni<Status> insert(Stored candidate) {
        // Once the writer is invoked, even an immediate throw/null/failure does not prove no insertion occurred.
        try {
            var write = store.insertIfAbsent(candidate);
            if (write == null) return item(Status.WRITE_OUTCOME_UNKNOWN);
            return write.map(ack -> {
                if (ack == null) return Status.WRITE_OUTCOME_UNKNOWN;
                if (ack.created()) return candidate.equals(ack.stored()) ? Status.CREATED : Status.WRITE_OUTCOME_UNKNOWN;
                return existing(ack.stored(), candidate.context(), candidate.result(), Status.WRITE_OUTCOME_UNKNOWN);
            }).onFailure().recoverWithItem(Status.WRITE_OUTCOME_UNKNOWN);
        } catch (RuntimeException uncertain) { return item(Status.WRITE_OUTCOME_UNKNOWN); }
    }

    private static Status existing(Stored stored, CompensatorySaccadeTrialContext context, CompensatorySaccadeTrialResult result, Status malformed) {
        if (!stored.context().equals(context) || !stored.result().therapySessionId().equals(result.therapySessionId())
                || !stored.result().trialId().equals(result.trialId())) return malformed;
        return stored.result().equals(result) ? Status.ALREADY_STORED : Status.CONFLICT;
    }

    private static Uni<Status> item(Status status) { return Uni.createFrom().item(status); }

    public enum Status { CREATED, ALREADY_STORED, CONFLICT, INVALID_RESULT, INVALID_TOKEN, DENIED, UNAVAILABLE, WRITE_OUTCOME_UNKNOWN }
}

package com.neurovision.rehab.analytics.application.internal.queryservices;

import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialSummaryAuthorizationPort.Permission;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultReader;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultStore.Stored;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import com.neurovision.rehab.analytics.domain.services.SustainedFixationTrialMetricsCalculator;
import com.neurovision.rehab.analytics.domain.services.SustainedFixationTrialMetricsCalculator.Summary;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Unwired read orchestration: fresh DOCTOR permission and one bounded observation per subscription. */
public final class SustainedFixationTrialSummaryService {
    public static final int MAX_TRIALS = 1000;
    private final SustainedFixationTrialSummaryAuthorizationPort authorization;
    private final SustainedFixationTrialResultReader reader;
    private final SustainedFixationTrialMetricsCalculator calculator = new SustainedFixationTrialMetricsCalculator();

    public SustainedFixationTrialSummaryService(SustainedFixationTrialSummaryAuthorizationPort authorization,
            SustainedFixationTrialResultReader reader) {
        this.authorization = Objects.requireNonNull(authorization);
        this.reader = Objects.requireNonNull(reader);
    }

    public Uni<Result> query(UUID session, DataOrigin origin, String originalDoctorAccessToken) {
        return Uni.createFrom().deferred(() -> {
            if (originalDoctorAccessToken == null || originalDoctorAccessToken.isBlank()) return item(Status.INVALID_TOKEN);
            if (session == null || origin == null) return item(Status.INVALID_QUERY);
            try {
                var decision = authorization.authorize(session, originalDoctorAccessToken);
                if (decision == null) return item(Status.UNAVAILABLE);
                if (decision.permission() != Permission.PERMITTED) return item(switch (decision.permission()) {
                    case INVALID_TOKEN -> Status.INVALID_TOKEN;
                    case DENIED -> Status.DENIED;
                    default -> Status.UNAVAILABLE;
                });
                var context = decision.context();
                if (!session.equals(context.therapySessionId())) return item(Status.UNAVAILABLE);
                var read = reader.read(session, origin, MAX_TRIALS + 1);
                if (read == null) return item(Status.UNAVAILABLE);
                return read.map(rows -> summarize(context, origin, rows))
                        .onFailure().recoverWithItem(new Result(Status.UNAVAILABLE, null));
            } catch (RuntimeException unavailable) { return item(Status.UNAVAILABLE); }
        });
    }

    private Result summarize(SustainedFixationTrialContext context, DataOrigin origin, List<Stored> rows) {
        if (rows == null || rows.size() > MAX_TRIALS + 1) return new Result(Status.UNAVAILABLE, null);
        // Validate the sentinel too: foreign/corrupt evidence must not masquerade as a valid limit rejection.
        for (var row : rows) {
            if (row == null || !context.equals(row.context()) || row.result().dataOrigin() != origin)
                return new Result(Status.UNAVAILABLE, null);
        }
        if (rows.size() > MAX_TRIALS) return new Result(Status.LIMIT_EXCEEDED, null);
        return new Result(Status.AVAILABLE, calculator.calculate(context, origin,
                rows.stream().map(Stored::result).toList()));
    }

    private static Uni<Result> item(Status status) { return Uni.createFrom().item(new Result(status, null)); }

    public enum Status { AVAILABLE, INVALID_QUERY, INVALID_TOKEN, DENIED, LIMIT_EXCEEDED, UNAVAILABLE }

    public record Result(Status status, Summary summary) {
        public Result {
            if (status == null || (status == Status.AVAILABLE ? summary == null : summary != null))
                throw new IllegalArgumentException("Invalid Sustained Fixation summary result");
        }
        @Override public String toString() { return "SustainedFixationSummaryResult[" + status + "]"; }
    }
}

package com.neurovision.rehab.telemetry.application.internal.queryservices;

import com.neurovision.rehab.telemetry.application.ports.out.authorization.HistoricalTelemetryReadAuthorizationPort;
import com.neurovision.rehab.telemetry.application.ports.out.persistence.HistoricalTelemetryReadPort;
import com.neurovision.rehab.telemetry.application.ports.out.persistence.HistoricalTelemetryReadPort.Cursor;
import com.neurovision.rehab.telemetry.application.ports.out.persistence.HistoricalTelemetryReadPort.Sample;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class QueryHistoricalTelemetrySamplesService {
    public static final int MAX_PAGE_SIZE = 200;
    public static final Duration MAX_WINDOW = Duration.ofMinutes(15);
    private final HistoricalTelemetryReadAuthorizationPort authorization;
    private final HistoricalTelemetryReadPort persistence;

    public QueryHistoricalTelemetrySamplesService(HistoricalTelemetryReadAuthorizationPort authorization,
            HistoricalTelemetryReadPort persistence) {
        this.authorization = Objects.requireNonNull(authorization);
        this.persistence = Objects.requireNonNull(persistence);
    }

    public Outcome query(UUID sessionId, UUID streamId, Instant from, Instant to,
            int limit, String cursorToken, String accessToken) {
        final Cursor cursor;
        try {
            if (sessionId == null || streamId == null || from == null || to == null
                    || !from.isBefore(to) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0
                    || limit < 1 || limit > MAX_PAGE_SIZE) return Outcome.error(Status.INVALID_REQUEST);
            cursor = cursorToken == null ? null : decode(cursorToken);
            if (cursor != null && (cursor.sampleTime().isBefore(from)
                    || !cursor.sampleTime().isBefore(to))) return Outcome.error(Status.INVALID_REQUEST);
        } catch (RuntimeException invalid) {
            return Outcome.error(Status.INVALID_REQUEST);
        }
        if (accessToken == null || accessToken.isBlank()) return Outcome.error(Status.UNAUTHENTICATED);
        final HistoricalTelemetryReadAuthorizationPort.Decision decision;
        try {
            decision = authorization.authorize(sessionId, accessToken);
        } catch (RuntimeException failed) {
            return Outcome.error(Status.UNAVAILABLE);
        }
        if (decision == HistoricalTelemetryReadAuthorizationPort.Decision.INVALID_TOKEN)
            return Outcome.error(Status.UNAUTHENTICATED);
        if (decision == HistoricalTelemetryReadAuthorizationPort.Decision.DENIED)
            return Outcome.error(Status.FORBIDDEN);
        if (decision != HistoricalTelemetryReadAuthorizationPort.Decision.PERMITTED)
            return Outcome.error(Status.UNAVAILABLE);
        try {
            List<Sample> fetched = persistence.read(sessionId, streamId, from, to, cursor, limit + 1);
            if (fetched == null || fetched.size() > limit + 1) return Outcome.error(Status.UNAVAILABLE);
            boolean more = fetched.size() > limit;
            List<Sample> samples = more ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
            return new Outcome(Status.OK, samples,
                    more ? encode(samples.getLast().cursor()) : null);
        } catch (RuntimeException failed) {
            return Outcome.error(Status.UNAVAILABLE);
        }
    }

    private static String encode(Cursor cursor) {
        String value = cursor.sampleTime() + "|" + cursor.batchId() + "|" + cursor.kind() + "|" + cursor.index();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String token) {
        if (token.isBlank() || token.length() > 256) throw new IllegalArgumentException("Invalid cursor");
        String[] fields = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)
                .split("\\|", -1);
        if (fields.length != 4) throw new IllegalArgumentException("Invalid cursor");
        String kind = fields[2];
        if (!kind.equals("GAZE") && !kind.equals("GAMEPLAY")) throw new IllegalArgumentException("Invalid cursor");
        int index = Integer.parseInt(fields[3]);
        if (index < 0) throw new IllegalArgumentException("Invalid cursor");
        return new Cursor(Instant.parse(fields[0]), UUID.fromString(fields[1]), kind, index);
    }

    public enum Status { OK, INVALID_REQUEST, UNAUTHENTICATED, FORBIDDEN, UNAVAILABLE }
    public record Outcome(Status status, List<Sample> samples, String nextCursor) {
        static Outcome error(Status status) { return new Outcome(status, List.of(), null); }
    }
}

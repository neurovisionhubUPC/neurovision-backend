package com.neurovision.rehab.telemetry.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.telemetry.application.ports.out.authorization.HistoricalTelemetryReadAuthorizationPort;
import com.neurovision.rehab.telemetry.application.ports.out.persistence.HistoricalTelemetryReadPort;
import com.neurovision.rehab.telemetry.application.ports.out.persistence.HistoricalTelemetryReadPort.Sample;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryHistoricalTelemetrySamplesServiceTest {
    private static final Instant FROM = Instant.parse("2026-09-17T12:00:00Z");
    private final UUID session = UUID.randomUUID();
    private final UUID stream = UUID.randomUUID();
    private final List<Sample> stored = new ArrayList<>();
    private HistoricalTelemetryReadAuthorizationPort.Decision decision =
            HistoricalTelemetryReadAuthorizationPort.Decision.PERMITTED;
    private int queries;

    @Test
    void rejectsOversizedInvalidAndForgedCursorsBeforeStorage() {
        var service = service();
        assertEquals(QueryHistoricalTelemetrySamplesService.Status.INVALID_REQUEST,
                service.query(session, stream, FROM, FROM.plusSeconds(901), 100, null, "jwt").status());
        assertEquals(QueryHistoricalTelemetrySamplesService.Status.INVALID_REQUEST,
                service.query(session, stream, FROM, FROM.plusSeconds(60), 201, null, "jwt").status());
        assertEquals(QueryHistoricalTelemetrySamplesService.Status.INVALID_REQUEST,
                service.query(session, stream, FROM, FROM.plusSeconds(60), 1, "bad", "jwt").status());
        assertEquals(0, queries);
    }

    @Test
    void authorizesEveryPageAndPreservesEqualTimestampContinuity() {
        var time = FROM.plusMillis(50);
        for (int i = 0; i < 3; i++) {
            stored.add(new Sample(time, new UUID(0, i + 1), i, "GAZE", 0, i,
                    time, 0.1, 0.2, 0.9, null, null, null, null, null, null, null));
        }
        var service = service();
        var first = service.query(session, stream, FROM, FROM.plusSeconds(60), 2, null, "jwt");
        assertEquals(2, first.samples().size());
        assertNotNull(first.nextCursor());
        var second = service.query(session, stream, FROM, FROM.plusSeconds(60), 2,
                first.nextCursor(), "jwt");
        assertEquals(List.of(stored.get(2)), second.samples());
        assertNull(second.nextCursor());
        assertEquals(2, queries);
        decision = HistoricalTelemetryReadAuthorizationPort.Decision.DENIED;
        assertEquals(QueryHistoricalTelemetrySamplesService.Status.FORBIDDEN,
                service.query(session, stream, FROM, FROM.plusSeconds(60), 2,
                        first.nextCursor(), "jwt").status());
        assertEquals(2, queries);
    }

    @Test
    void mapsIdentityAndClinicalFailuresWithoutQueryingSamples() {
        var service = service();
        decision = HistoricalTelemetryReadAuthorizationPort.Decision.INVALID_TOKEN;
        assertEquals(QueryHistoricalTelemetrySamplesService.Status.UNAUTHENTICATED,
                service.query(session, stream, FROM, FROM.plusSeconds(1), 1, null, "jwt").status());
        decision = HistoricalTelemetryReadAuthorizationPort.Decision.UNAVAILABLE;
        assertEquals(QueryHistoricalTelemetrySamplesService.Status.UNAVAILABLE,
                service.query(session, stream, FROM, FROM.plusSeconds(1), 1, null, "jwt").status());
        assertEquals(0, queries);
    }

    @Test
    void returnsEmptyAndGappedSegmentsWithoutInventingSamples() {
        var service = service();
        var empty = service.query(session, stream, FROM, FROM.plusSeconds(1), 2, null, "jwt");
        assertTrue(empty.samples().isEmpty());
        assertNull(empty.nextCursor());
        stored.add(new Sample(FROM.plusMillis(1), new UUID(0, 1), 0, "GAZE", 0, 1,
                FROM, 0.1, 0.2, 0.9, null, null, null, null, null, null, null));
        stored.add(new Sample(FROM.plusMillis(2), new UUID(0, 2), 2, "GAZE", 0, 2,
                FROM, 0.3, 0.4, 0.8, null, null, null, null, null, null, null));
        var gap = service.query(session, stream, FROM, FROM.plusSeconds(1), 2, null, "jwt");
        assertEquals(List.of(0L, 2L), gap.samples().stream().map(Sample::sequence).toList());
        assertNull(gap.nextCursor());
    }

    private QueryHistoricalTelemetrySamplesService service() {
        HistoricalTelemetryReadAuthorizationPort auth = (id, token) -> decision;
        HistoricalTelemetryReadPort persistence = (id, streamId, from, to, after, fetchLimit) -> {
            queries++;
            return stored.stream().filter(sample -> !sample.sampleTime().isBefore(from)
                    && sample.sampleTime().isBefore(to))
                    .filter(sample -> after == null || compare(sample, after) > 0)
                    .limit(fetchLimit).toList();
        };
        return new QueryHistoricalTelemetrySamplesService(auth, persistence);
    }

    private static int compare(Sample sample, HistoricalTelemetryReadPort.Cursor after) {
        int time = sample.sampleTime().compareTo(after.sampleTime());
        if (time != 0) return time;
        int batch = sample.batchId().compareTo(after.batchId());
        if (batch != 0) return batch;
        int kind = sample.kind().compareTo(after.kind());
        return kind != 0 ? kind : Integer.compare(sample.index(), after.index());
    }
}

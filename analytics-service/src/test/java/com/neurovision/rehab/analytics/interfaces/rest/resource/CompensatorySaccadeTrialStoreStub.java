package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultReader;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultStore;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Profile-only typed storage double; not MongoDB persistence evidence. */
@Alternative
@ApplicationScoped
public class CompensatorySaccadeTrialStoreStub implements CompensatorySaccadeTrialResultStore, CompensatorySaccadeTrialResultReader {
    private record Key(UUID session, String trial) { }
    record Observation(int reads, int writes, int records) { }
    private final ConcurrentHashMap<Key, Stored> rows = new ConcurrentHashMap<>();
    private final AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();

    private final AtomicInteger summaryReads = new AtomicInteger(), summaryLimit = new AtomicInteger();
    private final AtomicBoolean failSummaryRead = new AtomicBoolean();
    record SummaryObservation(int reads, int limit) { }

    @PostConstruct void admit() { VisualSearchTrialReceptionHttpProfile.requireOptIn(); }

    @Override public Uni<Optional<Stored>> find(UUID session, String trial) {
        return Uni.createFrom().item(() -> {
            reads.incrementAndGet();
            return Optional.ofNullable(rows.get(new Key(session, trial)));
        });
    }

    @Override public Uni<WriteResult> insertIfAbsent(Stored candidate) {
        return Uni.createFrom().item(() -> {
            writes.incrementAndGet();
            var result = candidate.result();
            var previous = rows.putIfAbsent(new Key(result.therapySessionId(), result.trialId()), candidate);
            return new WriteResult(previous == null, previous == null ? candidate : previous);
        });
    }

    @Override public Uni<List<Stored>> read(UUID session, DataOrigin origin, int maxRows) {
        return Uni.createFrom().deferred(() -> {
            summaryReads.incrementAndGet(); summaryLimit.set(maxRows);
            if (session == null || origin == null || maxRows < 1 || maxRows > 1001 || failSummaryRead.get())
                return Uni.createFrom().failure(new IllegalStateException("Synthetic summary observation unavailable"));
            return Uni.createFrom().item(rows.values().stream()
                    .filter(row -> session.equals(row.result().therapySessionId()) && row.result().dataOrigin() == origin)
                    .limit(maxRows).toList());
        });
    }
    /** Direct synthetic setup only: never reached through a production HTTP writer. */
    void seedSummary(List<Stored> fixture) {
        VisualSearchTrialReceptionHttpProfile.requireOptIn();
        if (fixture == null || fixture.size() > 1001 || rows.size() > 1001-fixture.size())
            throw new IllegalArgumentException("Invalid summary fixture");
        for (var row : fixture) {
            row.context().validate(row.result());
            if (rows.putIfAbsent(new Key(row.result().therapySessionId(),row.result().trialId()),row) != null)
                throw new IllegalArgumentException("Duplicate summary fixture identity");
        }
    }
    Set<Stored> summarySnapshot() { return Set.copyOf(rows.values()); }
    SummaryObservation summaryObservation() { return new SummaryObservation(summaryReads.get(),summaryLimit.get()); }
    void failSummaryReads(boolean fail) { failSummaryRead.set(fail); }
    void reset() {
        rows.clear(); reads.set(0); writes.set(0);
        summaryReads.set(0); summaryLimit.set(0); failSummaryRead.set(false);
    }
    Observation observation() { return new Observation(reads.get(), writes.get(), rows.size()); }
    Stored stored(UUID session, String trial) { return rows.get(new Key(session, trial)); }
}

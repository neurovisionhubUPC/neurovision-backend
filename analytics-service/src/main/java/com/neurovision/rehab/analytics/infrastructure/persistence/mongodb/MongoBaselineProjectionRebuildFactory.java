package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import com.neurovision.rehab.analytics.application.internal.commandservices.ApplyBaselineProjectionRebuildService;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import jakarta.enterprise.inject.Instance;
import java.util.Objects;

/** Explicit internal composition only: no CDI registration, client creation, I/O or automatic apply. */
public final class MongoBaselineProjectionRebuildFactory {
    private MongoBaselineProjectionRebuildFactory() { }

    /**
     * Borrows the caller's client without owning its lifecycle. The caller must obtain explicit
     * maintenance-write permission before apply; its boolean input is not authentication.
     */
    public static ApplyBaselineProjectionRebuildService create(Instance<ReactiveMongoClient> clients,
            String database, String inbox, String summaries) {
        Objects.requireNonNull(clients);
        if (database == null || database.isBlank() || inbox == null || inbox.isBlank()
                || summaries == null || summaries.isBlank() || inbox.equals(summaries)) {
            throw new IllegalArgumentException("Invalid rebuild storage locations");
        }
        return new ApplyBaselineProjectionRebuildService(
                new MongoProjectionRebuildObservationAdapter(clients, database, inbox, summaries),
                new MongoConditionalProjectionRebuildWriteAdapter(clients, database, summaries));
    }
}

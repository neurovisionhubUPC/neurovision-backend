package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.client.model.Collation;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultReader;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultStore.Stored;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import mutiny.zero.flow.adapters.AdaptersToFlow;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Read-only observation of existing trial records; no DDL, writes, aggregation or retained cursor. */
@ApplicationScoped
public class MongoSustainedFixationTrialResultReaderAdapter implements SustainedFixationTrialResultReader {
    interface RawReader {
        Multi<Document> find(Bson filter, int maxRows);
    }
    private final RawReader reader;
    private final Duration timeout;

    @Inject
    public MongoSustainedFixationTrialResultReaderAdapter(Instance<ReactiveMongoClient> clients,
            @ConfigProperty(name = "quarkus.mongodb.database") String database,
            @ConfigProperty(name = "analytics.sustained-fixation.results-collection", defaultValue = "sustained_fixation_trial_results_v1") String collection,
            @ConfigProperty(name = "analytics.sustained-fixation.persistence-timeout-ms", defaultValue = "5000") long timeoutMs) {
        this(nativeReader(clients, database, collection, checkedTimeout(timeoutMs)), checkedTimeout(timeoutMs));
    }

    MongoSustainedFixationTrialResultReaderAdapter(RawReader reader, Duration timeout) {
        this.reader = Objects.requireNonNull(reader);
        this.timeout = checkedTimeout(Objects.requireNonNull(timeout).toMillis());
    }

    @Override public Uni<List<Stored>> read(UUID session, DataOrigin origin, int maxRows) {
        return Uni.createFrom().deferred(() -> {
            if (session == null || origin == null || maxRows < 1 || maxRows > 1001) throw unavailable();
            var filter = and(eq("_id.therapySessionId", session.toString()), eq("result.dataOrigin", origin.name()));
            var rows = Objects.requireNonNull(reader.find(filter, maxRows));
            // The driver applies maxRows first. One extra client-side item detects a broken publisher
            // instead of silently truncating it, while bounding the collected list and cancelling it.
            return rows.select().first(maxRows + 1L).collect().asList().map(documents -> {
                if (documents.size() > maxRows) throw unavailable();
                return documents.stream().map(document -> {
                    var stored = MongoSustainedFixationTrialResultStoreAdapter.decode(document, document.get("_id", Document.class));
                    if (!session.equals(stored.result().therapySessionId()) || stored.result().dataOrigin() != origin)
                        throw unavailable();
                    return stored;
                }).toList();
            });
        }).ifNoItem().after(timeout).failWith(MongoSustainedFixationTrialResultReaderAdapter::unavailable)
                .onFailure().transform(failure -> unavailable());
    }

    private static RawReader nativeReader(Instance<ReactiveMongoClient> clients, String database,
            String collection, Duration timeout) {
        Objects.requireNonNull(clients);
        if (database == null || database.isBlank() || collection == null || collection.isBlank())
            throw new IllegalArgumentException("Invalid Sustained Fixation read location");
        return (filter, maxRows) -> {
            var publisher = clients.get().unwrap().getDatabase(database).getCollection(collection)
                    .withReadPreference(ReadPreference.primary()).withReadConcern(ReadConcern.MAJORITY)
                    .withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .find(filter).limit(maxRows).batchSize(maxRows)
                    .collation(Collation.builder().locale("simple").build());
            return Multi.createFrom().publisher(AdaptersToFlow.publisher(publisher));
        };
    }

    private static Duration checkedTimeout(long ms) {
        if (ms < 100 || ms > 10000) throw new IllegalArgumentException("Invalid Sustained Fixation read timeout");
        return Duration.ofMillis(ms);
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Sustained Fixation result read unavailable"); }
}

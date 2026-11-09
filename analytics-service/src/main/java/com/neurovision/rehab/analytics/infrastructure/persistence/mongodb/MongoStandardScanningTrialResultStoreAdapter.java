package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static com.mongodb.client.model.Filters.eq;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultStore;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import mutiny.zero.flow.adapters.AdaptersToFlow;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Insert-only adapter-owned collection. Built-in _id uniqueness, no DDL, updates, deletes or write retries. */
@ApplicationScoped
public class MongoStandardScanningTrialResultStoreAdapter implements StandardScanningTrialResultStore {
    interface RawOperations {
        Uni<Document> find(Document identity);
        Uni<Document> insert(Document command);
    }
    private final RawOperations operations;
    private final String collection;
    private final Duration timeout;

    @Inject
    public MongoStandardScanningTrialResultStoreAdapter(Instance<ReactiveMongoClient> clients,
            @ConfigProperty(name = "quarkus.mongodb.database") String database,
            @ConfigProperty(name = "analytics.standard-scanning.results-collection", defaultValue = "standard_scanning_trial_results_v1") String collection,
            @ConfigProperty(name = "analytics.standard-scanning.persistence-timeout-ms", defaultValue = "5000") long timeoutMs) {
        this(nativeOperations(clients, database, collection, checkedTimeout(timeoutMs)), collection, checkedTimeout(timeoutMs));
    }

    MongoStandardScanningTrialResultStoreAdapter(RawOperations operations, String collection, Duration timeout) {
        this.operations = Objects.requireNonNull(operations);
        if (collection == null || collection.isBlank()) throw new IllegalArgumentException("Invalid Standard Scanning collection");
        this.collection = collection;
        this.timeout = checkedTimeout(Objects.requireNonNull(timeout).toMillis());
    }

    @Override public Uni<Optional<Stored>> find(UUID session, String trial) {
        return bounded(Uni.createFrom().deferred(() -> read(identity(session, trial))));
    }

    @Override public Uni<WriteResult> insertIfAbsent(Stored candidate) {
        return bounded(Uni.createFrom().deferred(() -> {
            Objects.requireNonNull(candidate);
            Document key = identity(candidate.result().therapySessionId(), candidate.result().trialId());
            var command = new Document("insert", collection).append("ordered", true)
                    .append("documents", List.of(document(candidate)))
                    .append("writeConcern", Document.parse(WriteConcern.MAJORITY.withJournal(true).asDocument().toJson()));
            Uni<Document> write = Objects.requireNonNull(operations.insert(command));
            return write.chain(reply -> {
                if (reply == null || !one(reply.get("ok")) || reply.containsKey("writeConcernError")) throw unavailable();
                Object errors = reply.get("writeErrors");
                if (errors == null && reply.containsKey("writeErrors")) throw unavailable();
                if (errors == null || errors instanceof List<?> list && list.isEmpty()) {
                    if (integer(reply.get("n")) != 1) throw unavailable();
                    return Uni.createFrom().item(new WriteResult(true, candidate));
                }
                if (integer(reply.get("n")) != 0 || !(errors instanceof List<?> list) || list.size() != 1
                        || !(list.getFirst() instanceof Document error) || integer(error.get("index")) != 0
                        || integer(error.get("code")) != 11000) throw unavailable();
                // Only a confirmed single duplicate response permits one winner lookup, never another write.
                return read(key).map(winner -> new WriteResult(false, winner.orElseThrow(MongoStandardScanningTrialResultStoreAdapter::unavailable)));
            });
        }));
    }

    private Uni<Optional<Stored>> read(Document key) {
        return Objects.requireNonNull(operations.find(key)).map(row -> row == null ? Optional.empty() : Optional.of(decode(row, key)));
    }
    private <T> Uni<T> bounded(Uni<T> operation) {
        // One outer wait covers insert and optional winner read. Cancellation does not prove rollback.
        return operation.ifNoItem().after(timeout).failWith(MongoStandardScanningTrialResultStoreAdapter::unavailable)
                .onFailure().transform(failure -> unavailable());
    }

    static Document document(Stored stored) {
        var context = stored.context(); var result = stored.result();
        return new Document("_id", identity(result.therapySessionId(), result.trialId())).append("storageSchemaVersion", 1)
                .append("context", new Document("configurationId", context.configurationId().toString())
                        .append("configurationVersion", context.configurationVersion())
                        .append("minimumFixationMs", context.minimumFixationMs()).append("maximumStimulusDurationMs", context.maximumStimulusDurationMs()))
                .append("result", new Document("schemaVersion", result.schemaVersion()).append("outcome", result.outcome().name())
                        .append("localizationDurationMs", result.localizationDurationMs()).append("confirmedDwellMs", result.confirmedDwellMs())
                        .append("dataOrigin", result.dataOrigin().name()));
    }

    static Stored decode(Document row, Document requestedKey) {
        keys(row, Set.of("_id", "storageSchemaVersion", "context", "result"));
        if (integer(row.get("storageSchemaVersion")) != 1) throw unavailable();
        var key = row.get("_id", Document.class);
        keys(key, Set.of("therapySessionId", "trialId"));
        if (!requestedKey.equals(key)) throw unavailable();
        UUID session = uuid(key.get("therapySessionId"));
        String trial = text(key.get("trialId"));
        var context = row.get("context", Document.class); var result = row.get("result", Document.class);
        keys(context, Set.of("configurationId", "configurationVersion", "minimumFixationMs", "maximumStimulusDurationMs"));
        keys(result, Set.of("schemaVersion", "outcome", "localizationDurationMs", "confirmedDwellMs", "dataOrigin"));
        return new Stored(new StandardScanningTrialContext(session, uuid(context.get("configurationId")),
                integer(context.get("configurationVersion")), integer(context.get("minimumFixationMs")), integer(context.get("maximumStimulusDurationMs"))),
                new StandardScanningTrialResult(Math.toIntExact(integer(result.get("schemaVersion"))), session, trial,
                        VisualSearchTrialResult.Outcome.valueOf(text(result.get("outcome"))), integer(result.get("localizationDurationMs")),
                        result.get("confirmedDwellMs") == null ? null : integer(result.get("confirmedDwellMs")),
                        VisualSearchTrialResult.DataOrigin.valueOf(text(result.get("dataOrigin")))));
    }
    private static Document identity(UUID session, String trial) {
        if (session == null || trial == null || trial.isBlank()) throw unavailable();
        // Field order is stable and the trial is an opaque value, not a field name or concatenated key.
        return new Document("therapySessionId", session.toString()).append("trialId", trial);
    }
    private static void keys(Document value, Set<String> expected) {
        if (value == null || !value.keySet().equals(expected)) throw unavailable();
    }
    private static String text(Object value) { if (!(value instanceof String text)) throw unavailable(); return text; }
    private static UUID uuid(Object value) {
        String text = text(value); UUID id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw unavailable();
        return id;
    }
    private static long integer(Object value) {
        if (value instanceof Long number) return number;
        if (value instanceof Integer number) return number.longValue();
        throw unavailable();
    }
    private static boolean one(Object value) {
        return value instanceof Integer intValue && intValue == 1 || value instanceof Long longValue && longValue == 1L
                || value instanceof Double doubleValue && doubleValue == 1.0;
    }
    private static Duration checkedTimeout(long timeoutMs) {
        if (timeoutMs < 100 || timeoutMs > 10000) throw new IllegalArgumentException("Invalid Standard Scanning persistence timeout");
        return Duration.ofMillis(timeoutMs);
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Standard Scanning result storage unavailable"); }

    private static RawOperations nativeOperations(Instance<ReactiveMongoClient> clients, String database, String collection, Duration timeout) {
        Objects.requireNonNull(clients);
        if (database == null || database.isBlank() || collection == null || collection.isBlank())
            throw new IllegalArgumentException("Invalid Standard Scanning storage location");
        return new RawOperations() {
            private com.mongodb.reactivestreams.client.MongoDatabase database() { return clients.get().unwrap().getDatabase(database); }
            public Uni<Document> find(Document identity) {
                var publisher = database().getCollection(collection).withReadPreference(ReadPreference.primary())
                        .withReadConcern(ReadConcern.MAJORITY).withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .find(eq("_id", identity)).first();
                return Uni.createFrom().publisher(AdaptersToFlow.publisher(publisher));
            }
            public Uni<Document> insert(Document command) {
                // Driver 5.6.5 generic runCommand is non-retryable, unlike typed insertOne.
                var publisher = database().withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).runCommand(command, ReadPreference.primary());
                return Uni.createFrom().publisher(AdaptersToFlow.publisher(publisher));
            }
        };
    }
}

package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.ascending;
import com.mongodb.client.model.Collation;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSessionStreamsQueryPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MongoAnalyticsSessionStreamsQueryAdapter implements AnalyticsSessionStreamsQueryPort {
    private final Instance<ReactiveMongoClient> clients;
    private final String database;
    private final String collection;
    @Inject
    public MongoAnalyticsSessionStreamsQueryAdapter(Instance<ReactiveMongoClient> clients,
            @ConfigProperty(name="quarkus.mongodb.database") String database,
            @ConfigProperty(name="analytics.summary.collection") String collection) {
        this.clients=clients; this.database=database; this.collection=collection;
    }
    @Override
    public Uni<List<Item>> load(UUID sessionId, Page page) {
        Objects.requireNonNull(sessionId); Objects.requireNonNull(page);
        var options = new FindOptions().sort(ascending("streamId")).skip(page.offset()).limit(page.fetchLimit())
                .maxTime(5, TimeUnit.SECONDS).collation(Collation.builder().locale("simple").build());
        return clients.get().getDatabase(database).getCollection(collection)
                .find(and(eq("therapySessionId",sessionId.toString()),eq("calculationVersion","baseline-v1")),options)
                .collect().asList().map(documents -> decode(sessionId,page,documents));
    }
    static List<Item> decode(UUID sessionId, Page page, List<Document> documents) {
        Objects.requireNonNull(documents);
        if (documents.size()>page.fetchLimit()) throw new IllegalArgumentException("Unbounded discovery batch");
        var result = new ArrayList<Item>();
        String previous = null;
        for (var document : documents) {
            var raw = document.getString("streamId");
            var stream = UUID.fromString(raw);
            if (!stream.toString().equals(raw) || (previous!=null && previous.compareTo(raw)>=0))
                throw new IllegalArgumentException("Invalid discovery order or identity");
            var identity = new AnalyticsProjectionIdentity(sessionId,stream,"baseline-v1");
            var summary = MongoAnalyticsSummaryQueryAdapter.reconstruct(document,identity);
            MongoHeatmapProjectionQueryAdapter.reconstruct(document,identity);
            var counts = summary.summary().sampleCounts();
            result.add(new Item(stream,"baseline-v1",summary.revision(),counts.gaze(),counts.gameplay()));
            previous=raw;
        }
        return List.copyOf(result);
    }
}

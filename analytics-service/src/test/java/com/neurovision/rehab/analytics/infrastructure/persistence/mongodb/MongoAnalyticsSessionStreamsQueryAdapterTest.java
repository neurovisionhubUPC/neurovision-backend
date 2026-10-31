package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static org.junit.jupiter.api.Assertions.*;
import com.mongodb.client.model.Collation;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSessionStreamsQueryPort.Page;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.*;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;

class MongoAnalyticsSessionStreamsQueryAdapterTest {
    static final UUID SESSION=new UUID(0,1);
    static Document document(long stream) {
        var numeric=new BaselineStreamSummary.NumericSummary(0,OptionalDouble.empty(),OptionalDouble.empty(),OptionalDouble.empty());
        var ordered=new BaselineStreamSummary.OrderedNumericSummary(numeric,OptionalDouble.empty(),OptionalDouble.empty(),OptionalDouble.empty());
        var summary=new BaselineStreamSummary(AnalyticsCalculationProfile.BASELINE_V1,1,SESSION,new UUID(0,stream),0,0,List.of(),0,true,Optional.empty(),
                new BaselineStreamSummary.SampleCounts(0,0),numeric,Collections.nCopies(10,0L),
                new BaselineStreamSummary.Heatmap(10,10,Collections.nCopies(100,0L)),new BaselineStreamSummary.Coverage(Optional.empty(),0,0),
                new BaselineStreamSummary.PathMetrics(0,numeric,0),new BaselineStreamSummary.GameplayMetrics(numeric,ordered,ordered,ordered));
        return new Document("therapySessionId",SESSION.toString()).append("streamId",new UUID(0,stream).toString())
                .append("calculationVersion","baseline-v1").append("admittedBatchCount",1L).append("summary",MongoStreamSummaryProjectionAdapter.summaryDocument(summary));
    }
    @Test void validatesBothExistingDecodersAndReturnsZeroCountsWithoutInventingAbsence() {
        var values=MongoAnalyticsSessionStreamsQueryAdapter.decode(SESSION,new Page(0,2),List.of(document(1),document(2),document(3)));
        assertEquals(3,values.size());assertEquals(0,values.get(0).gaze());assertEquals(0,values.get(0).gameplay());assertEquals("baseline-v1",values.get(0).calculationVersion());
        assertEquals(List.of(),MongoAnalyticsSessionStreamsQueryAdapter.decode(SESSION,new Page(0,20),List.of()));
    }
    @Test void retainsObservedPositiveCountsFromTheExistingProductionProjectionEncoder() {
        var time=Instant.parse("2026-01-01T00:00:00Z");
        var accepted=new AcceptedTelemetryBatch(1,new UUID(0,4),new UUID(0,5),SESSION,new UUID(0,2),0,
                time,time.plusMillis(1),time.plusMillis(2),new UUID(0,6),null,
                List.of(new AcceptedTelemetryBatch.GazeSample(0,.2,.3,.9)),
                List.of(new AcceptedTelemetryBatch.GameplaySample(0,.1,.2,.3,.4,1,1.5,.6)),"synthetic");
        var summary=new BaselineTelemetryMetricsCalculator().calculate(List.of(new AdmittedTelemetryBatch(accepted,TelemetryAdmissionQuality.INITIAL)));
        var d=document(2);d.put("summary",MongoStreamSummaryProjectionAdapter.summaryDocument(summary));
        var item=MongoAnalyticsSessionStreamsQueryAdapter.decode(SESSION,new Page(0,20),List.of(d)).get(0);
        assertEquals(1,item.gaze());assertEquals(1,item.gameplay());assertEquals(2,item.total());
    }
    @Test void rejectsCorruptLookaheadWrongIdentityNoncanonicalDuplicateAndUnsortedRows() {
        var corrupt=document(3);corrupt.get("summary",Document.class).put("highSequence",99L);
        assertThrows(RuntimeException.class,()->MongoAnalyticsSessionStreamsQueryAdapter.decode(SESSION,new Page(0,2),List.of(document(1),document(2),corrupt)));
        for(String field:List.of("therapySessionId","streamId","calculationVersion")){
            var d=document(1);d.put(field,field.equals("calculationVersion")?"future":"not-a-uuid");
            assertThrows(RuntimeException.class,()->MongoAnalyticsSessionStreamsQueryAdapter.decode(SESSION,new Page(0,20),List.of(d)));
        }
        var foreign=document(1);foreign.put("therapySessionId",new UUID(0,2).toString());
        assertThrows(RuntimeException.class,()->MongoAnalyticsSessionStreamsQueryAdapter.decode(SESSION,new Page(0,20),List.of(foreign)));
        var shortId=document(1);shortId.put("streamId","0-0-0-0-1");
        assertThrows(RuntimeException.class,()->MongoAnalyticsSessionStreamsQueryAdapter.decode(SESSION,new Page(0,20),List.of(shortId)));
        for(var docs:List.of(List.of(document(1),document(1)),List.of(document(2),document(1)),List.of(document(1),document(2),document(3),document(4))))
            assertThrows(RuntimeException.class,()->MongoAnalyticsSessionStreamsQueryAdapter.decode(SESSION,new Page(0,2),docs));
    }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);}
    @Test void actualLoadUsesOneSessionVersionFilteredSortedBoundedQueryWithoutWrites() {
        var seen=new HashMap<String,Object>();
        var collection=proxy(ReactiveMongoCollection.class,(p,m,a)->{
            assertEquals("find",m.getName());assertEquals(2,a.length);seen.put("filter",a[0]);seen.put("options",a[1]);return Multi.createFrom().items(document(1));
        });
        var db=proxy(ReactiveMongoDatabase.class,(p,m,a)->{assertEquals("getCollection",m.getName());assertEquals("summaries",a[0]);return collection;});
        var client=proxy(ReactiveMongoClient.class,(p,m,a)->{assertEquals("getDatabase",m.getName());assertEquals("fixture",a[0]);return db;});
        Instance<ReactiveMongoClient> clients=proxy(Instance.class,(p,m,a)->{assertEquals("get",m.getName());return client;});
        var values=new MongoAnalyticsSessionStreamsQueryAdapter(clients,"fixture","summaries").load(SESSION,new Page(20,50)).await().indefinitely();
        assertEquals(1,values.size());
        var filter=((Bson)seen.get("filter")).toBsonDocument(Document.class,com.mongodb.MongoClientSettings.getDefaultCodecRegistry()).toJson();
        assertTrue(filter.contains(SESSION.toString()));assertTrue(filter.contains("baseline-v1"));assertFalse(filter.contains("streamId"));
        var options=new HashMap<String,Object>();
        FindPublisher<Document> publisher=proxy(FindPublisher.class,(p,m,a)->{if(a!=null&&a.length>0){options.put(m.getName(),a[0]);if(m.getName().equals("maxTime"))options.put("maxTimeUnit",a[1]);}return p;});
        ((FindOptions)seen.get("options")).apply(publisher);
        assertEquals(1000,options.get("skip"));assertEquals(51,options.get("limit"));
        assertEquals(5L,options.get("maxTime"));assertEquals(TimeUnit.SECONDS,options.get("maxTimeUnit"));
        assertEquals(Collation.builder().locale("simple").build(),options.get("collation"));
        var sort=((Bson)options.get("sort")).toBsonDocument(Document.class,com.mongodb.MongoClientSettings.getDefaultCodecRegistry());
        assertEquals(1,sort.getInt32("streamId").getValue());
    }
}

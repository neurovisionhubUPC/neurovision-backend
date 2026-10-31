package com.neurovision.rehab.clinical.infrastructure.integration;

import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.infrastructure.integration.ReportAnalyticsDecoderTest.*;

class AnalyticsSessionReportAdapterTest {
    @Test void forwardsOriginalTokenToExactlyFourSequentialFixedReads() {
        var calls=new ArrayList<ReportAnalyticsHttpClient.Resource>();
        var adapter=new AnalyticsSessionReportAdapter((session,stream,resource,token)->{
            assertEquals(SESSION,session); assertEquals("synthetic.original.token",token);
            calls.add(resource);
            if(resource==ReportAnalyticsHttpClient.Resource.DISCOVERY) { assertNull(stream); return discovery(); }
            assertEquals(STREAM,stream);
            return switch(resource) { case SUMMARY->summary(); case HEATMAP->heatmap(); case GAZE->gaze(); default->throw new AssertionError(); };
        });
        var result=(ObservedEvidence)adapter.observe(SESSION,"synthetic.original.token");
        assertEquals(List.of(ReportAnalyticsHttpClient.Resource.DISCOVERY,ReportAnalyticsHttpClient.Resource.SUMMARY,
                ReportAnalyticsHttpClient.Resource.HEATMAP,ReportAnalyticsHttpClient.Resource.GAZE),calls);
        assertEquals(1,result.streams().size()); assertEquals(0,result.discoveryPage()); assertEquals(20,result.discoverySize());
    }
    @Test void onlyValidEmptyFirstDiscoveryConfirmsAbsence() {
        var empty=discovery(); empty.withArray("items").removeAll();
        assertInstanceOf(ConfirmedNoEvidence.class,new AnalyticsSessionReportAdapter((s,i,r,t)->empty).observe(SESSION,"token"));
        empty.put("hasNext",true);
        assertThrows(IllegalStateException.class,()->new AnalyticsSessionReportAdapter((s,i,r,t)->empty).observe(SESSION,"token"));
        empty.put("hasNext",false).put("page",1);
        assertThrows(IllegalStateException.class,()->new AnalyticsSessionReportAdapter((s,i,r,t)->empty).observe(SESSION,"token"));
    }
    @Test void rejectsMismatchWithoutRetryOrLeakingThePeerFailure() {
        var calls=new AtomicInteger();
        var adapter=new AnalyticsSessionReportAdapter((s,i,r,t)->{
            calls.incrementAndGet();
            if(r==ReportAnalyticsHttpClient.Resource.DISCOVERY)return discovery();
            if(r==ReportAnalyticsHttpClient.Resource.SUMMARY)return summary().put("revision",2);
            throw new AssertionError("must stop after mismatch");
        });
        var error=assertThrows(IllegalStateException.class,()->adapter.observe(SESSION,"secret"));
        assertEquals(2,calls.get()); assertNull(error.getCause()); assertEquals("Report analytics unavailable",error.getMessage());
        var failure=new AnalyticsSessionReportAdapter((s,i,r,t)->{throw new IllegalStateException("secret payload");});
        assertEquals("Report analytics unavailable",assertThrows(IllegalStateException.class,()->failure.observe(SESSION,"secret")).getMessage());
    }
    @Test void preservesInterruptAndRejectsInvalidInputWithoutReading() {
        var adapter=new AnalyticsSessionReportAdapter((s,i,r,t)->{throw new InterruptedException("sensitive");});
        try { assertThrows(IllegalStateException.class,()->adapter.observe(SESSION,"token")); assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        var never=new AnalyticsSessionReportAdapter((s,i,r,t)->{throw new AssertionError();});
        assertThrows(IllegalStateException.class,()->never.observe(null,"token"));
        assertThrows(IllegalStateException.class,()->never.observe(SESSION," "));
    }

    @Test void readsAtMostSixtyOneTimesAndKeepsIndependentStreamsAndSelectionLimit() {
        var discovery=discovery(); discovery.withArray("items").removeAll(); discovery.put("hasNext",true);
        var ids=new ArrayList<UUID>();
        for(int i=0;i<20;i++) {
            var id=new UUID(0,100+i); ids.add(id); discovery.withArray("items").add(item(id));
        }
        var calls=new AtomicInteger();
        var adapter=new AnalyticsSessionReportAdapter((session,stream,resource,token)->{
            assertEquals("original",token); calls.incrementAndGet();
            return switch(resource) {
                case DISCOVERY->discovery;
                case SUMMARY->summary().put("streamId",stream.toString());
                case HEATMAP->heatmap().put("streamId",stream.toString());
                case GAZE->gaze();
            };
        });
        var result=(ObservedEvidence)adapter.observe(SESSION,"original");
        assertEquals(61,calls.get()); assertTrue(result.hasNext());
        assertEquals(ids,result.streams().stream().map(s->s.summary().source().streamId()).toList());
    }

    @Test void anyProjectionFailureOrNullRejectsTheWholeObservationWithoutRetry() {
        for(var failed:List.of(ReportAnalyticsHttpClient.Resource.SUMMARY,ReportAnalyticsHttpClient.Resource.HEATMAP,ReportAnalyticsHttpClient.Resource.GAZE)) {
            var calls=new AtomicInteger();
            var adapter=new AnalyticsSessionReportAdapter((session,stream,resource,token)->{
                calls.incrementAndGet();
                if(resource==failed)throw new IllegalStateException("peer token and payload");
                return switch(resource) { case DISCOVERY->discovery(); case SUMMARY->summary(); case HEATMAP->heatmap(); case GAZE->gaze(); };
            });
            var error=assertThrows(IllegalStateException.class,()->adapter.observe(SESSION,"original"));
            assertEquals(failed.ordinal()+1,calls.get()); assertNull(error.getCause());
            assertEquals("Report analytics unavailable",error.getMessage());
        }
        assertThrows(IllegalStateException.class,()->new AnalyticsSessionReportAdapter((s,i,r,t)->null).observe(SESSION,"original"));
    }
}

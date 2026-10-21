package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssignedPatientWireTest {
    final ObjectMapper mapper=new ObjectMapper();
    final UUID id=new UUID(0,1);
    final String valid="""
{"contractVersion":1,"patients":[{"clinicalPatientId":"00000000-0000-0000-0000-000000000001","nameStatus":"AVAILABLE","firstName":"Synthetic","lastName":"Patient"}]}
""";
    @Test void bindsExactIdsAndForwardsOriginalToken() throws Exception {
        var a=new ProfilePatientNamesAdapter(mapper,(token,body)->{
            assertEquals("original",token);assertEquals(3,body.size());
            assertEquals(id.toString(),body.path("clinicalPatientIds").get(0).textValue());
            return mapper.readTree(valid);
        });
        assertEquals(Set.of(id),a.lookup(Map.of(id,"synthetic-subject"),"original").keySet());
    }
    @Test void refusesMissingForeignExtraAndMalformedResponse() {
        for(String body:List.of("{}",valid.replace(id.toString(),new UUID(0,2).toString()),valid.replace("\"contractVersion\":1","\"contractVersion\":2"),valid.replace("\"patients\":","\"extra\":true,\"patients\":"))) {
            var a=new ProfilePatientNamesAdapter(mapper,(token,request)->mapper.readTree(body));
            assertThrows(PatientReadFailure.class,()->a.lookup(Map.of(id,"synthetic-subject"),"original"));
        }
    }
    @Test void boundsBodyAndDisablesRedirects() {
        var client=new PatientNameHttpClient(URI.create("http://peer:8080/fixed"),1000,mapper);
        var request=client.request("original",mapper.createObjectNode());
        assertEquals("Bearer original",request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("http://peer:8080/fixed",request.uri().toString());
        assertEquals(java.net.http.HttpClient.Redirect.NEVER,client.redirectPolicy());
        var sub=new PatientNameHttpClient.LimitedBody(4);
        final boolean[] cancelled={false};
        sub.onSubscribe(new Flow.Subscription(){public void request(long n){} public void cancel(){cancelled[0]=true;}});
        sub.onNext(List.of(ByteBuffer.wrap(new byte[5])));
        assertTrue(cancelled[0]);assertTrue(sub.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void acceptsMaximumUnicodeAndEscapedNamePages() throws Exception {
        for (String name : List.of("\u754c".repeat(100), "\u0001".repeat(100), "\ud83d\ude00".repeat(50), "\"\\".repeat(50))) {
            var rows=new ArrayList<Map<String,Object>>();
            var claims=new LinkedHashMap<UUID,String>();
            for (int i=1;i<=100;i++) {
                var patientId=new UUID(0,i);
                claims.put(patientId,"synthetic-subject-"+i);
                rows.add(Map.of("clinicalPatientId",patientId.toString(),"nameStatus","AVAILABLE",
                        "firstName",name,"lastName",name));
            }
            byte[] encoded=mapper.writeValueAsBytes(Map.of("contractVersion",1,"patients",rows));
            var sub=PatientNameHttpClient.responseBody();
            sub.onSubscribe(new Flow.Subscription(){public void request(long n){} public void cancel(){}});
            for (int offset=0;offset<encoded.length;offset+=1024)
                sub.onNext(List.of(ByteBuffer.wrap(encoded,offset,Math.min(1024,encoded.length-offset))));
            sub.onComplete();
            assertArrayEquals(encoded,sub.getBody().toCompletableFuture().get());
            var adapter=new ProfilePatientNamesAdapter(mapper,(token,body)->mapper.readTree(encoded));
            assertEquals(claims.keySet(),adapter.lookup(claims,"original").keySet());
        }
    }
    @Test void acceptsExactResponseLimitAndCancelsOneByteBeyondIt() throws Exception {
        assertEquals(132434,PatientNameHttpClient.MAX_RESPONSE_BYTES);
        var sub=PatientNameHttpClient.responseBody();
        final boolean[] cancelled={false};
        sub.onSubscribe(new Flow.Subscription(){public void request(long n){} public void cancel(){cancelled[0]=true;}});
        sub.onNext(List.of(ByteBuffer.wrap(new byte[PatientNameHttpClient.MAX_RESPONSE_BYTES-1])));
        sub.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        assertFalse(cancelled[0]);
        sub.onComplete();
        assertEquals(PatientNameHttpClient.MAX_RESPONSE_BYTES,sub.getBody().toCompletableFuture().get().length);

        var overflow=PatientNameHttpClient.responseBody();
        overflow.onSubscribe(new Flow.Subscription(){public void request(long n){} public void cancel(){cancelled[0]=true;}});
        overflow.onNext(List.of(ByteBuffer.wrap(new byte[PatientNameHttpClient.MAX_RESPONSE_BYTES]),ByteBuffer.wrap(new byte[1])));
        assertTrue(cancelled[0]);
        assertTrue(overflow.getBody().toCompletableFuture().isCompletedExceptionally());
        overflow.onComplete();
        assertTrue(overflow.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}

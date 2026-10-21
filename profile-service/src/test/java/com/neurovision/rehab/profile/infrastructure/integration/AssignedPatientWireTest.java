package com.neurovision.rehab.profile.infrastructure.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.profile.application.internal.queryservices.PatientNameFailure;
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
{"contractVersion":1,"patients":[{"clinicalPatientId":"00000000-0000-0000-0000-000000000001"}]}
""";
    @Test void bindsExactIdsAndForwardsOriginalToken() throws Exception {
        var a=new ClinicalPatientNameAuthorizationAdapter(mapper,(token,body)->{
            assertEquals("original",token);assertEquals(3,body.size());
            assertEquals(id.toString(),body.path("clinicalPatientIds").get(0).textValue());
            return mapper.readTree(valid);
        });
        assertEquals(Set.of(id),a.resolve(Map.of(id,"synthetic-subject"),"original").keySet());
    }
    @Test void refusesMissingForeignExtraAndMalformedResponse() {
        for(String body:List.of("{}",valid.replace(id.toString(),new UUID(0,2).toString()),valid.replace("\"contractVersion\":1","\"contractVersion\":2"),valid.replace("\"patients\":","\"extra\":true,\"patients\":"))) {
            var a=new ClinicalPatientNameAuthorizationAdapter(mapper,(token,request)->mapper.readTree(body));
            assertThrows(PatientNameFailure.class,()->a.resolve(Map.of(id,"synthetic-subject"),"original"));
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
}

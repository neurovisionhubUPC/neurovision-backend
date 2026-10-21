package com.neurovision.rehab.profile.interfaces.rest.resource;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.profile.application.internal.queryservices.PatientNameFailure;
import com.neurovision.rehab.profile.application.internal.queryservices.AssignedPatientNamesService;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import jakarta.ws.rs.core.Response;
import java.util.*;
import java.util.function.Supplier;
import static com.neurovision.rehab.profile.application.internal.queryservices.PatientNameFailure.Kind.*;

final class AssignedPatientRequestSupport {
    private final ObjectMapper mapper;
    AssignedPatientRequestSupport(ObjectMapper mapper) { this.mapper=Objects.requireNonNull(mapper); }
    Actor actor(SecurityIdentity identity) {
        if(identity==null || identity.isAnonymous()) throw new PatientNameFailure(UNAUTHENTICATED);
        if(!identity.hasRole("DOCTOR") || identity.hasRole("PATIENT") || !(identity.getPrincipal() instanceof JsonWebToken jwt)) throw new PatientNameFailure(FORBIDDEN);
        String subject=jwt.getSubject();
        if(subject==null || subject.isBlank() || subject.length()>128 || !subject.equals(subject.trim())) throw new PatientNameFailure(FORBIDDEN);
        var credential=identity.getCredential(AccessTokenCredential.class);
        if(credential==null || credential.getToken()==null || credential.getToken().isBlank()) throw new PatientNameFailure(UNAUTHENTICATED);
        return new Actor(subject,credential.getToken());
    }
    List<UUID> ids(String body) {
        try {
            if(body==null || body.length()>16384) throw new IllegalArgumentException();
            var json=mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if(json==null || !json.isObject() || json.size()!=2 || !json.path("contractVersion").isInt() || json.path("contractVersion").intValue()!=1
                    || !json.path("clinicalPatientIds").isArray() || json.path("clinicalPatientIds").size()>100) throw new IllegalArgumentException();
            var ids=new ArrayList<UUID>();
            for(var value:json.path("clinicalPatientIds")) {
                if(!value.isTextual()) throw new IllegalArgumentException();
                var id=UUID.fromString(value.textValue());if(!id.toString().equals(value.textValue())) throw new IllegalArgumentException();ids.add(id);
            }
            return AssignedPatientNamesService.ids(ids);
        } catch(Exception invalid) { throw new PatientNameFailure(INVALID_REQUEST); }
    }
    Map<UUID,String> targets(String body) {
        try {
            if(body==null || body.length()>16384) throw new IllegalArgumentException();
            var json=mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if(json==null || !json.isObject() || json.size()!=3 || !json.path("patientSubjects").isObject()) throw new IllegalArgumentException();
            var copy=(com.fasterxml.jackson.databind.node.ObjectNode)json.deepCopy();var subjects=copy.remove("patientSubjects");var ids=ids(copy.toString());
            if(subjects.size()!=ids.size()) throw new IllegalArgumentException();var result=new LinkedHashMap<UUID,String>();
            for(var id:ids) {
                var subject=subjects.get(id.toString());
                if(subject==null || !subject.isTextual() || subject.textValue().isBlank() || subject.textValue().length()>128 || !subject.textValue().equals(subject.textValue().trim())) throw new IllegalArgumentException();
                result.put(id,subject.textValue());
            }
            if(new HashSet<>(result.values()).size()!=result.size()) throw new IllegalArgumentException();return Collections.unmodifiableMap(result);
        } catch(Exception invalid) { throw new PatientNameFailure(INVALID_REQUEST); }
    }
    Response response(Supplier<Object> action) {
        try { return Response.ok(action.get()).header("Cache-Control","no-store").build(); }
        catch(PatientNameFailure failure) {
            int status=switch(failure.kind()) {case INVALID_REQUEST->400;case UNAUTHENTICATED->401;case FORBIDDEN->403;case UNAVAILABLE->503;};
            return error(status,"PATIENT_READ_"+failure.kind().name());
        } catch(IllegalArgumentException invalid) { return error(400,"PATIENT_READ_INVALID_REQUEST"); }
        catch(RuntimeException unavailable) { return error(503,"PATIENT_READ_UNAVAILABLE"); }
    }
    private Response error(int status,String code) { return Response.status(status).entity(Map.of("code",code)).header("Cache-Control","no-store").build(); }
    record Actor(String subject,String token) { @Override public String toString(){return "Actor[redacted]";} }
}

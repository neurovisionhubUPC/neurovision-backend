package com.neurovision.rehab.profile.infrastructure.integration;

import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.profile.application.ports.out.authorization.AssignedPatientNameAuthorizationPort;
import com.neurovision.rehab.profile.application.internal.queryservices.PatientNameFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ClinicalPatientNameAuthorizationAdapter implements AssignedPatientNameAuthorizationPort {
    private final ObjectMapper mapper;
    private final PatientNameHttpClient.Post post;
    @Inject
    public ClinicalPatientNameAuthorizationAdapter(@ConfigProperty(name="profile.clinical.base-url") URI base,
            @ConfigProperty(name="profile.clinical.timeout-ms") long timeout, ObjectMapper mapper) {
        this(mapper,new PatientNameHttpClient(base.resolve("/api/internal/v1/clinical-patients/profile-name-authorization"),timeout,mapper)::post);
    }
    public ClinicalPatientNameAuthorizationAdapter(ObjectMapper mapper, PatientNameHttpClient.Post post) { this.mapper=Objects.requireNonNull(mapper);this.post=Objects.requireNonNull(post); }
    @Override public Map<UUID,String> resolve(Map<UUID,String> targets,String token) {
        try {
            var ids=List.copyOf(targets.keySet());
            if(ids==null || ids.isEmpty() || ids.size()>100 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size()!=ids.size()) throw new IllegalArgumentException();
            var request=mapper.createObjectNode().put("contractVersion",1);
            var array=request.putArray("clinicalPatientIds");ids.forEach(id->array.add(id.toString()));
            var subjects=request.putObject("patientSubjects");targets.forEach((id,subject)->subjects.put(id.toString(),subject));
            var json=post.send(token,request);
            if(json==null || !json.isObject() || json.size()!=2 || !json.path("contractVersion").isInt() || json.path("contractVersion").intValue()!=1
                    || !json.path("patients").isArray() || json.path("patients").size()!=ids.size()) throw new IllegalArgumentException();
            var result=new LinkedHashMap<UUID,String>();
            for(var row:json.path("patients")) {
                if(!row.isObject() || !row.path("clinicalPatientId").isTextual()) throw new IllegalArgumentException();
                var id=UUID.fromString(row.path("clinicalPatientId").textValue());
                if(!id.toString().equals(row.path("clinicalPatientId").textValue())) throw new IllegalArgumentException();

                if(row.size()!=1) throw new IllegalArgumentException();
                var value=targets.get(id);
                if(value==null) throw new IllegalArgumentException();

                if(!ids.contains(id) || result.putIfAbsent(id,value)!=null) throw new IllegalArgumentException();
            }
            return Collections.unmodifiableMap(result);
        } catch (PatientNameFailure known) { throw known; }
        catch (Exception unavailable) { throw new PatientNameFailure(PatientNameFailure.Kind.UNAVAILABLE); }
    }
}

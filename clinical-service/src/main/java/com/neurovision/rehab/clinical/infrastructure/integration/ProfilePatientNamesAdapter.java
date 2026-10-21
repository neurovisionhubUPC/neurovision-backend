package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.AssignedPatientNamesPort;
import com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ProfilePatientNamesAdapter implements AssignedPatientNamesPort {
    private final ObjectMapper mapper;
    private final PatientNameHttpClient.Post post;
    @Inject
    public ProfilePatientNamesAdapter(@ConfigProperty(name="clinical.profile.base-url") URI base,
            @ConfigProperty(name="clinical.profile.timeout-ms") long timeout, ObjectMapper mapper) {
        this(mapper,new PatientNameHttpClient(base.resolve("/api/internal/v1/profiles/assigned-patient-names"),timeout,mapper)::post);
    }
    public ProfilePatientNamesAdapter(ObjectMapper mapper, PatientNameHttpClient.Post post) { this.mapper=Objects.requireNonNull(mapper);this.post=Objects.requireNonNull(post); }
    @Override public Map<UUID,Name> lookup(Map<UUID,String> targets,String token) {
        try {
            var ids=List.copyOf(targets.keySet());
            if(ids==null || ids.isEmpty() || ids.size()>100 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size()!=ids.size()) throw new IllegalArgumentException();
            var request=mapper.createObjectNode().put("contractVersion",1);
            var array=request.putArray("clinicalPatientIds");ids.forEach(id->array.add(id.toString()));
            var subjects=request.putObject("patientSubjects");targets.forEach((id,subject)->subjects.put(id.toString(),subject));
            var json=post.send(token,request);
            if(json==null || !json.isObject() || json.size()!=2 || !json.path("contractVersion").isInt() || json.path("contractVersion").intValue()!=1
                    || !json.path("patients").isArray() || json.path("patients").size()!=ids.size()) throw new IllegalArgumentException();
            var result=new LinkedHashMap<UUID,Name>();
            for(var row:json.path("patients")) {
                if(!row.isObject() || !row.path("clinicalPatientId").isTextual()) throw new IllegalArgumentException();
                var id=UUID.fromString(row.path("clinicalPatientId").textValue());
                if(!id.toString().equals(row.path("clinicalPatientId").textValue())) throw new IllegalArgumentException();

                if(row.size()!=4 || !row.path("nameStatus").isTextual()) throw new IllegalArgumentException();
                String first=nullableText(row.get("firstName")),last=nullableText(row.get("lastName"));
                var value=new Name(row.path("nameStatus").textValue(),first,last);

                if(!ids.contains(id) || result.putIfAbsent(id,value)!=null) throw new IllegalArgumentException();
            }
            return Collections.unmodifiableMap(result);
        } catch (PatientReadFailure known) { throw known; }
        catch (Exception unavailable) { throw new PatientReadFailure(PatientReadFailure.Kind.UNAVAILABLE); }
    }
    private static String nullableText(JsonNode node) {
        if(node==null) throw new IllegalArgumentException();
        if(node.isNull()) return null;
        if(!node.isTextual()) throw new IllegalArgumentException();
        return node.textValue();
    }
}

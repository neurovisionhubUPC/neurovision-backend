package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.AssignedPatientQueryService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v1/clinical-patients")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class DoctorClinicalPatientController {
    private final AssignedPatientQueryService queries;
    private final SecurityIdentity identity;
    private final AssignedPatientRequestSupport support;
    public DoctorClinicalPatientController(AssignedPatientQueryService queries,SecurityIdentity identity,ObjectMapper mapper) {
        this.queries=queries;this.identity=identity;this.support=new AssignedPatientRequestSupport(mapper);
    }
    @GET @RolesAllowed("DOCTOR")
    @Operation(summary="List assigned patients",description="Bounded current ACTIVE assignments only. Profile independently authorizes names; missing and pending names are explicit. No patient subjects or contact fields are returned.")
    public Response list(@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("20") int size) {
        return support.response(()->{
            var actor=support.actor(identity);
            var result=queries.list(actor.subject(),actor.token(),page,size);
            return Map.of("patients",result.patients().stream().map(v->patient(v,false)).toList(),"page",result.page(),"size",result.size(),"total",result.total());
        });
    }
    @GET @Path("/{clinicalPatientId}") @RolesAllowed("DOCTOR")
    @Operation(summary="Read assigned patient detail",description="Unknown and unassigned patients use the same denial. Read-only Clinical assessment plus independently authorized Profile names; no hospital import, writes, session history or reports.")
    public Response detail(@PathParam("clinicalPatientId") String rawId) {
        return support.response(()->{
            var actor=support.actor(identity);var id=UUID.fromString(rawId);
            if(!id.toString().equals(rawId)) throw new IllegalArgumentException();
            return patient(queries.detail(actor.subject(),actor.token(),id),true);
        });
    }
    static Map<String,Object> patient(AssignedPatientQueryService.View view,boolean detail) {
        var p=view.patient();var name=view.name();var result=new LinkedHashMap<String,Object>();
        result.put("clinicalPatientId",p.id());result.put("status",p.status());result.put("createdAt",p.createdAt());result.put("updatedAt",p.updatedAt());
        result.put("nameStatus",name.status());result.put("firstName",name.firstName());result.put("lastName",name.lastName());
        if(detail){result.put("strokeDiagnosis",p.diagnosis());result.put("sequelae",p.sequelae());}
        return result;
    }
}

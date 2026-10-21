package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.neurovision.rehab.clinical.application.internal.commandservices.DoctorSchedulingUnavailableException;
import com.neurovision.rehab.clinical.application.ports.in.*;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import jakarta.ws.rs.WebApplicationException;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoctorSchedulingControllerBoundaryTest {
    private SecurityIdentity doctor() {
        var jwt = (JsonWebToken) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{JsonWebToken.class},
                (p,m,a) -> m.getName().equals("getSubject") ? "synthetic-doctor" : null);
        return (SecurityIdentity) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SecurityIdentity.class},
                (p,m,a) -> switch (m.getName()) {
                    case "hasRole" -> "DOCTOR".equals(a[0]);
                    case "getPrincipal" -> jwt;
                    default -> null;
                });
    }
    @Test void mapsMissingLicenseAndDependencyFailureWithoutPrivateDetail() throws Exception {
        var request = new CreateTherapyPlanRequest(2, 30, null, 5, 15, 50, Map.of("fixation", 1));
        var configuration = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().readValue("""
                {"exerciseCode":"fixation","parameterSchemaVersion":1,"effectiveFrom":"2099-01-01T00:00:00Z",
                 "parameters":{"mode":"SUSTAINED_TARGET_FIXATION","targetPosition":"CENTER",
                 "minimumFixationMs":500,"maximumFixationMs":1000,"targetSizePx":50,
                 "repetitions":10,"restBetweenRepetitionsMs":800,"difficultyLevel":2}}
                """, AppendTherapeuticConfigurationRequest.class);
        for (int status : new int[]{403,503}) {
            TherapyPlanCreationUseCase creator = command -> {
                if (status == 403) throw new SecurityException("private detail");
                throw new DoctorSchedulingUnavailableException();
            };
            TherapeuticConfigurationAppendUseCase appender = command -> {
                if (status == 403) throw new SecurityException("private detail");
                throw new DoctorSchedulingUnavailableException();
            };
            var controller = new DoctorTherapyPlanController(creator, appender, doctor());
            var failure = assertThrows(WebApplicationException.class, () -> controller.create(new UUID(0,1).toString(), request));
            assertEquals(status, failure.getResponse().getStatus());
            assertFalse(failure.getMessage().contains("private"));
            var appendFailure = assertThrows(WebApplicationException.class, () -> controller.append(new UUID(0,1).toString(), new UUID(0,2).toString(), configuration));
            assertEquals(status, appendFailure.getResponse().getStatus());
        }
    }
}

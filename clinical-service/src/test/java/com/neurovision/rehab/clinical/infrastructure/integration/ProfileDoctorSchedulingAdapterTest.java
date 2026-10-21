package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.internal.commandservices.DoctorSchedulingUnavailableException;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfileDoctorSchedulingAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CognitoSubject doctor = new CognitoSubject("synthetic-doctor");
    private SecurityIdentity identity(boolean anonymous, Set<String> roles, String subject, String credential) {
        var jwt = (JsonWebToken) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{JsonWebToken.class},
                (p,m,a) -> m.getName().equals("getSubject") ? subject : null);
        return (SecurityIdentity) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SecurityIdentity.class},
                (p,m,a) -> switch (m.getName()) {
                    case "isAnonymous" -> anonymous;
                    case "hasRole" -> roles.contains(a[0]);
                    case "getPrincipal" -> jwt;
                    case "getCredential" -> credential == null ? null : new AccessTokenCredential(credential);
                    default -> null;
                });
    }
    private SecurityIdentity validIdentity() { return identity(false, Set.of("DOCTOR"), doctor.value(), "original"); }
    private JsonNode profile(String type, String status, String cmp) {
        return mapper.createObjectNode().put("profileType", type).put("status", status).put("medicalLicense", cmp);
    }
    @Test void forwardsOnlyOriginalDoctorTokenAndAcceptsCanonicalCmp() {
        int[] calls = {0};
        var adapter = new ProfileDoctorSchedulingAdapter(validIdentity(), token -> {
            assertEquals("original", token); calls[0]++; return profile("DOCTOR", "ACTIVE", "012345");
        });
        adapter.requireEligible(doctor);
        assertEquals(1, calls[0]);
    }
    @Test void rejectsWrongIdentityBeforePeerLookup() {
        for (var identity : List.of(identity(true, Set.of("DOCTOR"), doctor.value(), "original"),
                identity(false, Set.of("PATIENT"), doctor.value(), "original"),
                identity(false, Set.of("DOCTOR", "PATIENT"), doctor.value(), "original"),
                identity(false, Set.of("DOCTOR", "ADMIN"), doctor.value(), "original"),
                identity(false, Set.of("DOCTOR"), "different-subject", "original"),
                identity(false, Set.of("DOCTOR"), doctor.value(), null),
                identity(false, Set.of("DOCTOR"), doctor.value(), " "))) {
            var adapter = new ProfileDoctorSchedulingAdapter(identity, token -> { fail("Unexpected Profile call"); return null; });
            assertThrows(SecurityException.class, () -> adapter.requireEligible(doctor));
        }
    }
    @Test void rejectsAbsentInvalidLegacyOrNonDoctorProfiles() {
        for (JsonNode result : List.of(profile("DOCTOR", "ACTIVE", null), profile("DOCTOR", "PENDING_COMPLETION", "012345"),
                profile("PATIENT", "ACTIVE", "012345"), profile("DOCTOR", "ACTIVE", "CMP-123456"),
                profile("DOCTOR", "ACTIVE", "000000"), profile("DOCTOR", "ACTIVE", "12345"),
                profile("DOCTOR", "ACTIVE", "1234567"), profile("DOCTOR", "ACTIVE", " 012345 "),
                profile("DOCTOR", "ACTIVE", "１２３４５６"), profile("DOCTOR", "ACTIVE", "123#45"))) {
            var adapter = new ProfileDoctorSchedulingAdapter(validIdentity(), token -> result);
            assertThrows(SecurityException.class, () -> adapter.requireEligible(doctor));
        }
    }
    @Test void malformedAndUnavailableResponsesFailClosedWithSafeErrors() {
        var invalid = List.of(mapper.createObjectNode(), mapper.createArrayNode(),
                mapper.createObjectNode().put("profileType", "DOCTOR").put("status", "ACTIVE").put("medicalLicense", 123456));
        for (JsonNode result : invalid) {
            var adapter = new ProfileDoctorSchedulingAdapter(validIdentity(), token -> result);
            assertThrows(DoctorSchedulingUnavailableException.class, () -> adapter.requireEligible(doctor));
        }
        var adapter = new ProfileDoctorSchedulingAdapter(validIdentity(), token -> { throw new java.io.IOException("private upstream detail"); });
        var failure = assertThrows(DoctorSchedulingUnavailableException.class, () -> adapter.requireEligible(doctor));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("private"));
    }
}

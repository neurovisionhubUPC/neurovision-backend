package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neurovision.rehab.clinical.application.ports.out.integration.DoctorReportIdentityPort.Availability;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfileDoctorReportIdentityAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode profile(String status) {
        return mapper.createObjectNode().put("profileType", "DOCTOR").put("status", status)
                .put("firstName", "Synthetic").put("lastName", "Doctor")
                .putNull("specialty").putNull("medicalLicense");
    }

    @Test void usesAuthenticatedSubjectAndOriginalSelfTokenWithoutSchedulingCmpGate() {
        int[] calls = {0};
        var body = profile("ACTIVE").put("specialty", "Neurology").put("medicalLicense", "legacy-CMP");
        var adapter = new ProfileDoctorReportIdentityAdapter(token -> {
            calls[0]++;
            assertEquals("original-token", token);
            return new ReportProfileHttpClient.Profile(body, false);
        });
        var identity = adapter.lookup("synthetic-doctor", "original-token");
        assertEquals(1, calls[0]);
        assertEquals("synthetic-doctor", identity.doctorSubject());
        assertEquals(Availability.AVAILABLE, identity.availability());
        assertEquals("Synthetic", identity.firstName());
        assertEquals("Doctor", identity.lastName());
        assertEquals("Neurology", identity.specialty());
        assertEquals("legacy-CMP", identity.medicalLicense());
        body.putNull("medicalLicense");
        assertNull(adapter.lookup("synthetic-doctor", "original-token").medicalLicense());
    }

    @Test void pendingAndExplicitMissingHaveNoIdentityFields() {
        var pending = profile("PENDING_COMPLETION").putNull("firstName").putNull("lastName");
        for (var result : List.of(new ReportProfileHttpClient.Profile(pending, false),
                new ReportProfileHttpClient.Profile(null, true))) {
            var identity = new ProfileDoctorReportIdentityAdapter(token -> result).lookup("synthetic-doctor", "original");
            assertEquals(result.missing() ? Availability.MISSING : Availability.PENDING_COMPLETION, identity.availability());
            assertNull(identity.firstName()); assertNull(identity.lastName());
            assertNull(identity.specialty()); assertNull(identity.medicalLicense());
        }
    }

    @Test void malformedFieldsTypesAndUnsupportedStatesNeverBecomeMissing() {
        for (String field : List.of("profileType", "status", "firstName", "lastName", "specialty", "medicalLicense")) {
            var invalid = profile("ACTIVE"); invalid.put(field, 3);
            fails(new ReportProfileHttpClient.Profile(invalid, false));
            invalid = profile("ACTIVE"); invalid.remove(field);
            fails(new ReportProfileHttpClient.Profile(invalid, false));
        }
        fails(new ReportProfileHttpClient.Profile(profile("ACTIVE").put("profileType", "PATIENT"), false));
        fails(new ReportProfileHttpClient.Profile(profile("UNKNOWN"), false));
        fails(new ReportProfileHttpClient.Profile(profile("ACTIVE").put("firstName", " "), false));
        fails(new ReportProfileHttpClient.Profile(profile("ACTIVE").put("lastName", "x".repeat(201)), false));
        fails(new ReportProfileHttpClient.Profile(profile("PENDING_COMPLETION").put("specialty", false), false));
        fails(new ReportProfileHttpClient.Profile(mapper.createArrayNode(), false));
        fails(new ReportProfileHttpClient.Profile(null, false));
        fails(new ReportProfileHttpClient.Profile(profile("ACTIVE"), true));
        fails(null);
    }

    private void fails(ReportProfileHttpClient.Profile result) {
        var error = assertThrows(IllegalStateException.class, () ->
                new ProfileDoctorReportIdentityAdapter(token -> result).lookup("synthetic-doctor", "original"));
        assertEquals("Report Profile identity unavailable", error.getMessage());
        assertNull(error.getCause());
    }

    @Test void invalidCallerInputsFailBeforeRequestAndUpstreamErrorsAreSanitized() {
        var adapter = new ProfileDoctorReportIdentityAdapter(token -> { fail("Unexpected Profile request"); return null; });
        for (String subject : new String[]{null, " ", " synthetic-doctor", "x".repeat(129)}) {
            assertThrows(IllegalStateException.class, () -> adapter.lookup(subject, "original"));
        }
        for (String token : new String[]{null, " ", "x".repeat(16385), "unsafe\r\nvalue"}) {
            assertThrows(IllegalStateException.class, () -> adapter.lookup("synthetic-doctor", token));
        }
        for (Exception failure : List.of(new IOException("private upstream"), new SecurityException("private token"))) {
            var failing = new ProfileDoctorReportIdentityAdapter(token -> { throw failure; });
            var error = assertThrows(IllegalStateException.class, () -> failing.lookup("synthetic-doctor", "original"));
            assertEquals("Report Profile identity unavailable", error.getMessage()); assertNull(error.getCause());
        }
    }

    @Test void preservesInterruptWithoutLeakingDependencyDetails() {
        try {
            var adapter = new ProfileDoctorReportIdentityAdapter(token -> { throw new InterruptedException("private"); });
            assertThrows(IllegalStateException.class, () -> adapter.lookup("synthetic-doctor", "original"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}

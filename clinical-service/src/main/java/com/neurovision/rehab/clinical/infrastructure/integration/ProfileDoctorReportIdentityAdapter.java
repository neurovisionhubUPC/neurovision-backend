package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.DoctorReportIdentityPort;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import java.net.URI;
import java.util.Objects;
import static com.neurovision.rehab.clinical.infrastructure.integration.ReportProfileHttpClient.unavailable;

/** Current report identity only; neither scheduling eligibility nor CMP validation is a report gate.
 * The authenticated report-preview boundary supplies a DOCTOR subject and its original token.
 * Profile's self response has no subject: binding relies on that original-token self request and
 * the supplied authenticated subject. This adapter does not independently authenticate either.
 * Registered through SessionReportServicesConfiguration, not as a second class-level CDI bean.
 */
public final class ProfileDoctorReportIdentityAdapter implements DoctorReportIdentityPort {
    private final ReportProfileHttpClient.Get get;

    public ProfileDoctorReportIdentityAdapter(URI base, long timeoutMs, ObjectMapper mapper) {
        this(new ReportProfileHttpClient(base, timeoutMs, mapper)::get);
    }

    public ProfileDoctorReportIdentityAdapter(ReportProfileHttpClient.Get get) {
        this.get = Objects.requireNonNull(get);
    }

    @Override public Identity lookup(String authenticatedDoctorSubject, String originalAccessToken) {
        try {
            if (!new CognitoSubject(authenticatedDoctorSubject).value().equals(authenticatedDoctorSubject)) throw unavailable();
            ReportProfileHttpClient.requireToken(originalAccessToken);
            var response = Objects.requireNonNull(get.send(originalAccessToken));
            if (response.missing()) {
                if (response.body() != null) throw unavailable();
                return new Identity(authenticatedDoctorSubject, Availability.MISSING, null, null, null, null);
            }
            var body = response.body();
            if (body == null || !body.isObject() || !"DOCTOR".equals(requiredText(body, "profileType"))) throw unavailable();
            var status = requiredText(body, "status");
            var firstName = nullableText(body, "firstName");
            var lastName = nullableText(body, "lastName");
            var specialty = nullableText(body, "specialty");
            var medicalLicense = nullableText(body, "medicalLicense");
            if ("PENDING_COMPLETION".equals(status)) {
                return new Identity(authenticatedDoctorSubject, Availability.PENDING_COMPLETION, null, null, null, null);
            }
            if (!"ACTIVE".equals(status)) throw unavailable();
            return new Identity(authenticatedDoctorSubject, Availability.AVAILABLE, firstName, lastName, specialty, medicalLicense);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (Exception failure) { throw unavailable(); }
    }

    private static String requiredText(JsonNode body, String name) {
        var value = nullableText(body, name);
        if (value == null) throw unavailable();
        return value;
    }

    private static String nullableText(JsonNode body, String name) {
        var value = body.get(name);
        if (value == null) throw unavailable();
        if (value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 200) throw unavailable();
        return value.textValue();
    }
}

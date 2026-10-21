package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorSchedulingEligibilityPort;
import com.neurovision.rehab.clinical.application.internal.commandservices.DoctorSchedulingUnavailableException;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** Uses the same authenticated actor and original token; never a caller-supplied profile ID. */
@RequestScoped
public class ProfileDoctorSchedulingAdapter implements DoctorSchedulingEligibilityPort {
    private final SecurityIdentity identity;
    private final DoctorProfileHttpClient.Get get;

    @Inject
    public ProfileDoctorSchedulingAdapter(SecurityIdentity identity,
            @ConfigProperty(name = "clinical.profile.base-url") URI base,
            @ConfigProperty(name = "clinical.profile.timeout-ms") long timeout, ObjectMapper mapper) {
        this(identity, new DoctorProfileHttpClient(base, timeout, mapper)::get);
    }

    public ProfileDoctorSchedulingAdapter(SecurityIdentity identity, DoctorProfileHttpClient.Get get) {
        this.identity = Objects.requireNonNull(identity);
        this.get = Objects.requireNonNull(get);
    }

    @Override public void requireEligible(CognitoSubject doctor) {
        if (doctor == null || identity.isAnonymous() || !identity.hasRole("DOCTOR")
                || identity.hasRole("PATIENT") || identity.hasRole("ADMIN")
                || !(identity.getPrincipal() instanceof JsonWebToken jwt)
                || !doctor.value().equals(jwt.getSubject())) throw denied();
        var credential = identity.getCredential(AccessTokenCredential.class);
        if (credential == null || credential.getToken() == null || credential.getToken().isBlank()
                || credential.getToken().length() > 16384) throw denied();
        try {
            var profile = get.send(credential.getToken());
            if (profile == null || !profile.isObject() || !profile.path("profileType").isTextual()
                    || !profile.path("status").isTextual() || !profile.has("medicalLicense")) {
                throw new DoctorSchedulingUnavailableException();
            }
            if (!profile.path("profileType").textValue().equals("DOCTOR")
                    || !profile.path("status").textValue().equals("ACTIVE")) throw denied();
            var cmp = profile.get("medicalLicense");
            if (cmp.isNull()) throw denied();
            if (!cmp.isTextual()) throw new DoctorSchedulingUnavailableException();
            // Profile stores canonical new values. Never silently normalize legacy values here.
            if (!cmp.textValue().matches("[0-9]{6}") || cmp.textValue().equals("000000")) throw denied();
        } catch (SecurityException | DoctorSchedulingUnavailableException known) { throw known; }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new DoctorSchedulingUnavailableException();
        } catch (Exception unavailable) { throw new DoctorSchedulingUnavailableException(); }
    }

    private static SecurityException denied() { return new SecurityException("Doctor scheduling is not permitted"); }
}

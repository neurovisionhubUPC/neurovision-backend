package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.*;
import com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotService;
import com.neurovision.rehab.clinical.application.ports.out.integration.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.infrastructure.integration.*;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.Produces;
import java.nio.file.*;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionReportServicesConfigurationTest {
    final SessionReportServicesConfiguration configuration = new SessionReportServicesConfiguration();
    final ObjectMapper mapper = new ObjectMapper();

    @Test void constructsReviewedAdaptersWithoutMakingRequestsAndRejectsUnsafeConfiguration() {
        assertInstanceOf(ProfileDoctorReportIdentityAdapter.class, configuration.doctorReportIdentity("http://localhost:8082", 4000, mapper));
        assertInstanceOf(AnalyticsSessionReportAdapter.class, configuration.sessionReportAnalytics("http://localhost:8085", 4000, mapper));
        assertInstanceOf(AnalyticsVisualSearchReportAdapter.class,configuration.sessionReportVisualSearch("http://localhost:8085",4000,mapper));
        for (String base : List.of("file:///tmp/report", "http://user@localhost", "http://localhost/path", "http://localhost?query=x", "http://localhost#fragment")) {
            assertThrows(IllegalArgumentException.class, () -> configuration.doctorReportIdentity(base, 4000, mapper));
            assertThrows(IllegalArgumentException.class, () -> configuration.sessionReportAnalytics(base, 4000, mapper));
            assertThrows(IllegalArgumentException.class, () -> configuration.sessionReportVisualSearch(base,4000,mapper));
        }
        for (long timeout : List.of(99L, 10001L)) {
            assertThrows(IllegalArgumentException.class, () -> configuration.doctorReportIdentity("http://localhost:8082", timeout, mapper));
            assertThrows(IllegalArgumentException.class, () -> configuration.sessionReportAnalytics("http://localhost:8085", timeout, mapper));
            assertThrows(IllegalArgumentException.class, () -> configuration.sessionReportVisualSearch("http://localhost:8085",timeout,mapper));
        }
    }

    @Test void compositionProducerReusesSuppliedClinicalQueriesAndStopsOnTheirDenial() {
        int[] calls = {0};
        var sessions = new DoctorTherapySessionQueryService(new DoctorTherapySessionReadRepository() {
            public Observation observe(UUID p, String d, Filters f, int o, int l) { throw new AssertionError(); }
            public Optional<Detail> observeDetail(UUID p, String d, UUID s) { calls[0]++; return Optional.empty(); }
        });
        AssignedPatientNamesPort names = (targets, token) -> { throw new AssertionError("No identity reads after denial"); };
        var patients = new AssignedPatientQueryService(new AssignedPatientReadPort() {
            public Page list(String d, int o, int l) { throw new AssertionError(); }
            public Optional<Patient> detail(String d, UUID p) { throw new AssertionError(); }
            public Map<UUID, String> resolve(String d, List<UUID> ids) { throw new AssertionError(); }
        }, names);
        var service = configuration.sessionReports(sessions, patients, names,
                (d, t) -> { throw new AssertionError(); }, (s, t) -> { throw new AssertionError(); }, (s,o,t) -> { throw new AssertionError(); });
        assertThrows(SecurityException.class, () -> service.compose("synthetic-doctor", "synthetic-token", new UUID(0, 1), new UUID(0, 2)));
        assertEquals(1, calls[0]);
    }

    @Test void producerAnnotationsUseExistingProfileKeysAndDedicatedAnalyticsKeys() throws Exception {
        assertNotNull(SessionReportServicesConfiguration.class.getAnnotation(ApplicationScoped.class));
        for (var method : SessionReportServicesConfiguration.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertNotNull(method.getAnnotation(Produces.class)); assertNotNull(method.getAnnotation(ApplicationScoped.class));
            if (method.getReturnType() == ComposeSessionReportService.class) {
                assertArrayEquals(new Class<?>[]{DoctorTherapySessionQueryService.class, AssignedPatientQueryService.class,
                        AssignedPatientNamesPort.class, DoctorReportIdentityPort.class, SessionReportAnalyticsPort.class, SessionReportVisualSearchPort.class}, method.getParameterTypes());
            } else if (method.getReturnType() == DoctorReportIdentityPort.class || method.getReturnType() == SessionReportAnalyticsPort.class || method.getReturnType() == SessionReportVisualSearchPort.class) {
                String prefix = method.getReturnType() == DoctorReportIdentityPort.class ? "clinical.profile" : "clinical.analytics";
                assertEquals(prefix + ".base-url", method.getParameters()[0].getAnnotation(ConfigProperty.class).name());
                assertEquals(prefix + ".timeout-ms", method.getParameters()[1].getAnnotation(ConfigProperty.class).name());
            }
        }
        var properties = new Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) { properties.load(input); }
        assertEquals("${PROFILE_BASE_URL:http://localhost:8082}", properties.getProperty("clinical.profile.base-url"));
        assertEquals("4000", properties.getProperty("clinical.profile.timeout-ms"));
        assertEquals("${ANALYTICS_BASE_URL:http://localhost:8085}", properties.getProperty("clinical.analytics.base-url"));
        assertEquals("4000", properties.getProperty("clinical.analytics.timeout-ms"));
    }

    @Test void snapshotProducersReuseCompositionAndWhitelistWithoutSerializingInternalRecords() throws Exception {
        var f = new com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.Fixture();
        var encoder = configuration.snapshotEncoder(mapper);
        var report = f.composer().compose("synthetic-doctor", "synthetic-token", new UUID(0, 1), new UUID(0, 2));
        var json = mapper.readTree(encoder.encode(report));
        assertFalse(json.toString().contains("synthetic-doctor"));
        assertFalse(json.path("doctor").has("doctorSubject"));
        assertEquals(Set.of("availability", "firstName", "lastName", "specialty", "medicalLicense"), fields(json.path("doctor")));
        var create = configuration.createReportSnapshots(f, f.composer(), encoder);
        var saved = create.create("synthetic-doctor", "synthetic-token", new UUID(0, 1), new UUID(0, 2), "key").snapshot();
        assertEquals(saved, configuration.getReportSnapshots(f).get("synthetic-doctor", new UUID(0, 1), new UUID(0, 2), saved.id()).orElseThrow());
        assertArrayEquals(new Class<?>[]{SessionReportSnapshotRepository.class, ComposeSessionReportService.class, SessionReportSnapshotEncoder.class},
                SessionReportServicesConfiguration.class.getDeclaredMethod("createReportSnapshots", SessionReportSnapshotRepository.class,
                        ComposeSessionReportService.class, SessionReportSnapshotEncoder.class).getParameterTypes());
    }

    @Test void listingProducerOnlyNeedsItsReadOnlyPortAndDoesNotReadEagerly() {
        int[] calls = {0};
        var listing = configuration.listReportSnapshots((doctor, patient, session, offset, limit) -> {
            calls[0]++;
            return new com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotListRepository.ListObservation(true, List.of(), 0);
        });
        assertEquals(0, calls[0]);
        assertTrue(listing.list("synthetic-doctor", new UUID(0,1), new UUID(0,2), 0,20).snapshots().isEmpty());
        assertEquals(1, calls[0]);
    }

    private static Set<String> fields(com.fasterxml.jackson.databind.JsonNode node) {
        var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add); return names;
    }

    @Test void sourceBeanDeclarationsHaveExactlyOneProviderForEverySharedAndNewGraphType() throws Exception {
        var types = List.of(ComposeSessionReportService.class, DoctorReportIdentityPort.class, SessionReportAnalyticsPort.class, SessionReportVisualSearchPort.class,
                DoctorTherapySessionQueryService.class, AssignedPatientQueryService.class, AssignedPatientNamesPort.class,
                SessionReportSnapshotRepository.class, SessionReportSnapshotEncoder.class, CreateSessionReportSnapshotService.class, GetSessionReportSnapshotService.class);
        var counts = new HashMap<Class<?>, Integer>(); types.forEach(type -> counts.put(type, 0));
        var root = Path.of("src/main/java");
        try (var files = Files.walk(root)) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = root.relativize(file).toString().replace('\\', '.').replace('/', '.').replaceAll("\\.java$", "");
                var bean = Class.forName(name, false, getClass().getClassLoader());
                boolean scoped = bean.isAnnotationPresent(ApplicationScoped.class) || bean.isAnnotationPresent(RequestScoped.class)
                        || bean.isAnnotationPresent(Dependent.class) || bean.isAnnotationPresent(jakarta.inject.Singleton.class);
                for (var type : types) {
                    if (scoped && type.isAssignableFrom(bean)) counts.compute(type, (k, v) -> v + 1);
                    for (var method : bean.getDeclaredMethods())
                        if (method.isAnnotationPresent(Produces.class) && type.isAssignableFrom(method.getReturnType()))
                            counts.compute(type, (k, v) -> v + 1);
                }
            }
        }
        types.forEach(type -> assertEquals(1, counts.get(type), type.getSimpleName()));
    }
}

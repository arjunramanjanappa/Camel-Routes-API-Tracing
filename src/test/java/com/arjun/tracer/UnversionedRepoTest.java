package com.arjun.tracer;

import com.arjun.tracer.api.ApiDiff;
import com.arjun.tracer.api.CatalogResponse;
import com.arjun.tracer.api.ImpactIndex;
import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.api.VersionDiffReport;
import com.arjun.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A repo that is NOT versioned — its routes never carry an {@code R<version>_} prefix, so every API
 * always uses its base route. Handled by the {@code N/A} ("latest per API, else base") client-version
 * token: with no versioned route, latest is never found and resolution falls through to the base route,
 * across all three tabs. Versioned repos are unaffected — {@code N/A} there picks each API's newest.
 */
class UnversionedRepoTest {

    private static String bootstrap() {
        return "<beans xmlns=\"http://www.springframework.org/schema/beans\">"
                + "<import resource=\"classpath:routes.xml\"/>"
                + "<camelContext id=\"ctx\" xmlns=\"http://camel.apache.org/schema/spring\">"
                + "<routeContextRef ref=\"base\"/></camelContext></beans>";
    }

    private static String route(String id, String backend) {
        return "<route id=\"" + id + "\"><from uri=\"direct:" + id + "\"/>"
                + "<setProperty name=\"api\"><simple>" + backend + "</simple></setProperty></route>";
    }

    /** Base routes only — no R<version>_ prefix anywhere. */
    private static String unversionedRoutes() {
        return "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                + "<routeContext id=\"base\">"
                + route("prospect", "/bfs/prospect")
                + route("enquiry", "/bfs/enquiry")
                + "</routeContext></beans:beans>";
    }

    private static final String CONTROLLER = """
            package com.x.sg;
            import org.springframework.web.bind.annotation.*;
            @RequestMapping("/services/sg")
            @RestController
            public class C {
                @CommandHandler @PostMapping("/prospect") public Object prospect(Object b){ return null; }
                @CommandHandler @PostMapping("/enquiry")  public Object enquiry(Object b){ return null; }
            }
            """;

    private RouteTraceService unversionedRepo(Path dir) throws Exception {
        Files.writeString(dir.resolve("SG.xml"), bootstrap());
        Files.writeString(dir.resolve("routes.xml"), unversionedRoutes());
        Files.writeString(dir.resolve("C.java"), CONTROLLER);
        return new RouteTraceService(dir.toString());
    }

    @Test
    void naResolvesEachApiToItsBaseRoute(@TempDir Path dir) throws Exception {
        RouteTraceService service = unversionedRepo(dir);

        TraceResponse r = service.trace(new TraceRequest("prospect", "N/A", null, null, "SG"));
        assertThat(r.getResolvedRoute()).isEqualTo("prospect");
        assertThat(r.getResolvedVersion()).isNull();
        assertThat(r.isBaseFallback()).isTrue();
        assertThat(r.getFlow()).contains("prospect");
        assertThat(r.getBackendApis()).contains("/bfs/prospect");
    }

    @Test
    void catalogWithNaListsEveryApiUnderBase(@TempDir Path dir) throws Exception {
        RouteTraceService service = unversionedRepo(dir);

        CatalogResponse cat = (CatalogResponse) service.analyze(
                new TraceRequest(null, "N/A", null, null, "SG"));
        // One BASE group holding both APIs — not excluded as "not impacted".
        assertThat(cat.getVersionsFound()).containsExactly("BASE");
        assertThat(cat.getGroups()).hasSize(1);
        assertThat(cat.getGroups().get(0).traces())
                .extracting(TraceResponse::getApi)
                .containsExactlyInAnyOrder("/services/sg/prospect", "/services/sg/enquiry");
    }

    @Test
    void impactWithNaIncludesEveryApi(@TempDir Path dir) throws Exception {
        RouteTraceService service = unversionedRepo(dir);

        ImpactIndex idx = service.impactIndex(new TraceRequest(null, "N/A", null, null, "SG"));
        assertThat(idx.getApis())
                .extracting(a -> a.api())
                .containsExactlyInAnyOrder("/services/sg/prospect", "/services/sg/enquiry");
    }

    @Test
    void diffWithNaShowsCleanNotVersionedRowsNotMisleadingNotes(@TempDir Path dir) throws Exception {
        RouteTraceService service = unversionedRepo(dir);

        VersionDiffReport diff = service.versionDiff(new TraceRequest(null, "N/A", null, null, "SG"));
        assertThat(diff.getChangedCount()).isZero();
        assertThat(diff.getNewCount()).isZero();
        assertThat(diff.getUnchangedCount()).isEqualTo(2);
        assertThat(diff.getApis()).allSatisfy(a -> {
            assertThat(a.status()).isEqualTo(ApiDiff.UNCHANGED);
            assertThat(a.note()).contains("Not versioned");
            assertThat(a.note()).doesNotContain("No N/A route");
        });
    }

    // --- a versioned repo is unaffected: N/A picks each API's newest release ---

    @Test
    void naPicksTheLatestVersionWhenTheRepoIsVersioned(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("SG.xml"),
                "<beans xmlns=\"http://www.springframework.org/schema/beans\">"
                        + "<import resource=\"classpath:routes.xml\"/>"
                        + "<camelContext id=\"ctx\" xmlns=\"http://camel.apache.org/schema/spring\">"
                        + "<routeContextRef ref=\"base\"/></camelContext></beans>");
        Files.writeString(dir.resolve("routes.xml"),
                "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                        + "<routeContext id=\"base\">"
                        + route("R9.1_prospect", "/bfs/prospect")
                        + route("R9.3_prospect", "/bfs/prospect")
                        + "</routeContext></beans:beans>");
        Files.writeString(dir.resolve("C.java"), """
                package com.x.sg;
                import org.springframework.web.bind.annotation.*;
                @RequestMapping("/services/sg")
                @RestController
                public class C {
                    @CommandHandler @PostMapping("/prospect") public Object prospect(Object b){ return null; }
                }
                """);
        RouteTraceService service = new RouteTraceService(dir.toString());

        TraceResponse r = service.trace(new TraceRequest("prospect", "N/A", null, null, "SG"));
        assertThat(r.getResolvedRoute()).isEqualTo("R9.3_prospect");
        assertThat(r.getResolvedVersion()).isEqualTo("9.3");
        assertThat(r.isBaseFallback()).isFalse();
    }
}

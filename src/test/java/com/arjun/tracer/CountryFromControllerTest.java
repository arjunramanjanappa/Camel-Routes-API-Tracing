package com.arjun.tracer;

import com.arjun.tracer.api.ApiDiff;
import com.arjun.tracer.api.ApiImpact;
import com.arjun.tracer.api.CatalogResponse;
import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-country repos (SPL) have one controller per country — same class name, same handler method
 * (so the same operation name and the same shared Camel route), differing only by the class-level
 * {@code @RequestMapping("/services/<country>")} (or a {@code .<country>} package). The country a
 * selected scope shows must come from the CONTROLLER, not the shared route — otherwise every country
 * lists every country's copy of the API.
 */
class CountryFromControllerTest {

    private static String bootstrap() {
        return "<beans xmlns=\"http://www.springframework.org/schema/beans\">"
                + "<import resource=\"classpath:shared/routes.xml\"/>"
                + "<camelContext id=\"camelContext\" xmlns=\"http://camel.apache.org/schema/spring\">"
                + "<routeContextRef ref=\"sharedCtx\"/></camelContext></beans>";
    }

    /** One shared route the framework resolves the API to, imported by every country bootstrap. */
    private static String sharedRoutes() {
        return "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                + "<routeContext id=\"sharedCtx\">"
                + "<route id=\"R9.14_softtoken\">"
                + "<from uri=\"direct:R9.14_softtoken\"/>"
                + "<setProperty name=\"api\"><simple>/bfs/token</simple></setProperty>"
                + "</route></routeContext></beans:beans>";
    }

    private static String controller(String pkg, String requestMapping) {
        return "package " + pkg + ";\n"
                + "import org.springframework.web.bind.annotation.*;\n"
                + (requestMapping == null ? "" : "@RequestMapping(\"" + requestMapping + "\")\n")
                + "@RestController\n"
                + "public class PublicApiController {\n"
                + "  @CommandHandler @PostMapping(\"/public/security/softtoken\")\n"
                + "  public Object softtoken(Object b){ return null; }\n"
                + "}\n";
    }

    private List<String> apisFor(RouteTraceService service, String country) {
        CatalogResponse cat = (CatalogResponse) service.analyze(
                new TraceRequest(null, "9.14", null, null, country));
        return cat.getGroups().stream()
                .flatMap(g -> g.traces().stream())
                .map(TraceResponse::getApi)
                .toList();
    }

    @Test
    void countryFromRequestMappingListsOnlyThatCountrysCopy(@TempDir Path dir) throws Exception {
        for (String c : new String[]{"SG", "MY", "ID"}) {
            Files.writeString(dir.resolve(c + ".xml"), bootstrap());
        }
        Files.createDirectories(dir.resolve("shared"));
        Files.writeString(dir.resolve("shared/routes.xml"), sharedRoutes());
        Files.writeString(dir.resolve("SgController.java"), controller("com.x.y.sg", "/services/sg"));
        Files.writeString(dir.resolve("MyController.java"), controller("com.x.y.my", "/services/my"));
        Files.writeString(dir.resolve("IdController.java"), controller("com.x.y.id", "/services/id"));

        RouteTraceService service = new RouteTraceService(dir.toString());

        assertThat(apisFor(service, "MY")).containsExactly("/services/my/public/security/softtoken");
        assertThat(apisFor(service, "SG")).containsExactly("/services/sg/public/security/softtoken");
        assertThat(apisFor(service, "ID")).containsExactly("/services/id/public/security/softtoken");
    }

    /**
     * Country is mandatory on ALL three tabs and they all share {@code prepare()} (scoped registry) and
     * {@code operationsInScope()} (controller-country filter) — so the same "only that country's copy"
     * scoping must hold for Release Test (impact index) and Release Impact (version diff), not just
     * Release Scope. Same 3-controllers-over-one-shared-route framework as above.
     */
    @Test
    void controllerCountryScopingAppliesToImpactAndDiffTabsToo(@TempDir Path dir) throws Exception {
        for (String c : new String[]{"SG", "MY", "ID"}) {
            Files.writeString(dir.resolve(c + ".xml"), bootstrap());
        }
        Files.createDirectories(dir.resolve("shared"));
        Files.writeString(dir.resolve("shared/routes.xml"), sharedRoutes());
        Files.writeString(dir.resolve("SgController.java"), controller("com.x.y.sg", "/services/sg"));
        Files.writeString(dir.resolve("MyController.java"), controller("com.x.y.my", "/services/my"));
        Files.writeString(dir.resolve("IdController.java"), controller("com.x.y.id", "/services/id"));

        RouteTraceService service = new RouteTraceService(dir.toString());

        // Release Test (impact index): only MY's copy of the shared API.
        assertThat(service.impactIndex(new TraceRequest(null, "9.14", null, null, "MY")).getApis())
                .extracting(ApiImpact::api)
                .containsExactly("/services/my/public/security/softtoken");

        // Release Impact (version diff): only MY's copy of the shared API.
        assertThat(service.versionDiff(new TraceRequest(null, "9.14", null, null, "MY")).getApis())
                .extracting(ApiDiff::api)
                .containsExactly("/services/my/public/security/softtoken");
    }

    @Test
    void countryFallsBackToTheControllerPackageWhenRequestMappingHasNone(@TempDir Path dir) throws Exception {
        for (String c : new String[]{"SG", "MY"}) {
            Files.writeString(dir.resolve(c + ".xml"), bootstrap());
        }
        Files.createDirectories(dir.resolve("shared"));
        Files.writeString(dir.resolve("shared/routes.xml"), sharedRoutes());
        // A country-specific BAU api: no /services/<country> in @RequestMapping — the MY package decides it.
        Files.writeString(dir.resolve("MyBauController.java"), controller("com.x.y.my", "/bau"));

        RouteTraceService service = new RouteTraceService(dir.toString());

        // Lists under MY (package ends .my), never under SG.
        assertThat(apisFor(service, "MY")).containsExactly("/bau/public/security/softtoken");
        assertThat(apisFor(service, "SG")).isEmpty();
    }
}

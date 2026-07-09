package com.uob.tracer;

import com.uob.tracer.api.CatalogResponse;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.TraceResponse;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A common code base with per-country bootstraps. SG pulls in {@code sgContext}
 * (via routeContextRef) and a common file (via import); MY pulls in
 * {@code myContext}. Scoping to a country must include only that country's
 * assembly closure.
 */
class CountryScopingTest {

    private RouteTraceService service;

    private static String beans(String body) {
        return "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                + body + "</beans:beans>";
    }

    private static String routeContext(String id, String routeId, String backend) {
        return beans("<routeContext id=\"" + id + "\">"
                + "<route id=\"" + routeId + "\">"
                + "<from uri=\"direct:" + routeId + "\"/>"
                + "<setProperty name=\"api\"><simple>" + backend + "</simple></setProperty>"
                + "<log message=\"x\"/></route></routeContext>");
    }

    private static String bootstrap(String ref, String importResource) {
        String imp = importResource == null ? "" : "<import resource=\"" + importResource + "\"/>";
        return "<beans xmlns=\"http://www.springframework.org/schema/beans\">" + imp
                + "<camelContext id=\"camelContext\" xmlns=\"http://camel.apache.org/schema/spring\">"
                + "<routeContextRef ref=\"" + ref + "\"/></camelContext></beans>";
    }

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("SG.xml"), bootstrap("sgContext", "classpath:routes/common.xml"));
        Files.writeString(dir.resolve("MY.xml"), bootstrap("myContext", null));
        Files.createDirectories(dir.resolve("sg"));
        Files.writeString(dir.resolve("sg/sg-routes.xml"),
                routeContext("sgContext", "sgOnlyRoute", "{{baseUrl}}/sg"));
        Files.createDirectories(dir.resolve("my"));
        Files.writeString(dir.resolve("my/my-routes.xml"),
                routeContext("myContext", "myOnlyRoute", "{{baseUrl}}/my"));
        Files.createDirectories(dir.resolve("routes"));
        Files.writeString(dir.resolve("routes/common.xml"),
                routeContext("commonContext", "commonRoute", "{{baseUrl}}/common"));

        // A UFW controller whose handler method names match the route names above, so the
        // catalog can map APIs to routes per country.
        Files.writeString(dir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @CommandHandler @PostMapping("/sg")     public Object sgOnlyRoute(Object b){ return null; }
                    @CommandHandler @PostMapping("/my")     public Object myOnlyRoute(Object b){ return null; }
                    @CommandHandler @PostMapping("/common") public Object commonRoute(Object b){ return null; }
                }
                """);

        service = new RouteTraceService(dir.toString());
    }

    private TraceResponse trace(String country, String operation) {
        return service.trace(new TraceRequest(operation, null, null, null, country));
    }

    @Test
    void listsAvailableCountries() {
        assertThat(service.listCountries(new TraceRequest(null, null, null, null)))
                .containsExactly("MY", "SG");
    }

    @Test
    void sgScopeIncludesItsRefAndImportButNotMyRoutes() {
        TraceResponse sgOwn = trace("SG", "sgOnlyRoute");
        assertThat(sgOwn.getCountry()).isEqualTo("SG");
        assertThat(sgOwn.getFlow()).contains("sgOnlyRoute");
        assertThat(sgOwn.getBackendApis()).contains("{{baseUrl}}/sg");

        TraceResponse sgCommon = trace("SG", "commonRoute");      // reached via <import>
        assertThat(sgCommon.getFlow()).contains("commonRoute");
        assertThat(sgCommon.getWarnings()).noneMatch(w -> w.contains("Route not found"));

        TraceResponse sgSeesMy = trace("SG", "myOnlyRoute");      // MY route excluded
        assertThat(sgSeesMy.getWarnings()).anyMatch(w -> w.contains("Route not found"));
        assertThat(sgSeesMy.getBackendApis()).isEmpty();
    }

    @Test
    void myScopeIncludesOnlyMyRoutes() {
        assertThat(trace("MY", "myOnlyRoute").getFlow()).contains("myOnlyRoute");
        assertThat(trace("MY", "sgOnlyRoute").getWarnings()).anyMatch(w -> w.contains("Route not found"));
        assertThat(trace("MY", "commonRoute").getWarnings()).anyMatch(w -> w.contains("Route not found"));
    }

    @Test
    void noCountryScopeSeesEveryCountrysRoutes() {
        assertThat(trace(null, "sgOnlyRoute").getFlow()).contains("sgOnlyRoute");
        assertThat(trace(null, "myOnlyRoute").getFlow()).contains("myOnlyRoute");
        assertThat(trace(null, "commonRoute").getFlow()).contains("commonRoute");
    }

    @Test
    void catalogUnderACountryListsOnlyThatCountrysWiredApis() {
        // SG is wired for sgOnlyRoute (its routeContextRef) and commonRoute (its import);
        // myOnlyRoute is MY-only, so it must NOT appear in the SG catalog.
        CatalogResponse sg = (CatalogResponse) service.analyze(new TraceRequest(null, null, null, null, "SG"));
        java.util.List<String> sgOps = sg.getGroups().stream()
                .flatMap(g -> g.traces().stream())
                .map(TraceResponse::getOperationName)
                .toList();
        assertThat(sgOps).contains("sgOnlyRoute", "commonRoute");
        assertThat(sgOps).doesNotContain("myOnlyRoute");
        assertThat(sg.getWarnings()).anyMatch(w -> w.contains("not wired into SG"));

        // MY sees only myOnlyRoute; sg/common are omitted from the MY view.
        CatalogResponse my = (CatalogResponse) service.analyze(new TraceRequest(null, null, null, null, "MY"));
        java.util.List<String> myOps = my.getGroups().stream()
                .flatMap(g -> g.traces().stream())
                .map(TraceResponse::getOperationName)
                .toList();
        assertThat(myOps).contains("myOnlyRoute");
        assertThat(myOps).doesNotContain("sgOnlyRoute", "commonRoute");

        // With NO country, every API is listed.
        CatalogResponse allC = (CatalogResponse) service.analyze(new TraceRequest(null, null, null, null, null));
        java.util.List<String> allOps = allC.getGroups().stream()
                .flatMap(g -> g.traces().stream())
                .map(TraceResponse::getOperationName)
                .toList();
        assertThat(allOps).contains("sgOnlyRoute", "myOnlyRoute", "commonRoute");
    }
}

package com.arjun.tracer;

import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Under a COUNTRY scope (Release Scope with SG), the dynamic
 * {@code <toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>} must still resolve — the dest
 * route it computes has to be in SG's scope. Reproduces the reported "Unresolved dynamic target in
 * R9.14_prospectRoute" + "routes flagged as not found" when the country closure is used.
 */
class DynamicDestCountryScopeTest {

    /** SG bootstrap: refs the entry routeContext and imports the file with the dest routes. */
    private void writeFramework(Path dir) throws Exception {
        Files.writeString(dir.resolve("SG.xml"),
                "<beans xmlns=\"http://www.springframework.org/schema/beans\">"
                        + "<import resource=\"classpath:common/dest.xml\"/>"
                        + "<camelContext id=\"camelContext\" xmlns=\"http://camel.apache.org/schema/spring\">"
                        + "<routeContextRef ref=\"sgContext\"/></camelContext></beans>");
        Files.createDirectories(dir.resolve("sg"));
        Files.writeString(dir.resolve("sg/entry.xml"),
                "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                        + "<routeContext id=\"sgContext\">"
                        + "<route id=\"R9.14_prospectRoute\">"
                        + "<from uri=\"direct:R9.14_prospect\"/>"
                        + "<setProperty name=\"DEST_ROUTE\"><constant>prospectDetails</constant></setProperty>"
                        + "<toD uri=\"direct:${exchangeProperty[FINAL_ROUTE_NAME]}\"/>"
                        + "</route></routeContext></beans:beans>");
        Files.createDirectories(dir.resolve("common"));
        Files.writeString(dir.resolve("common/dest.xml"),
                "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                        + "<routeContext id=\"commonContext\">"
                        + "<route id=\"R9.14_prospectDetails\">"
                        + "<from uri=\"direct:R9.14_prospectDetails\"/>"
                        + "<setProperty name=\"api\"><simple>/bfs/prospect</simple></setProperty>"
                        + "</route></routeContext></beans:beans>");
        Files.writeString(dir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @CommandHandler @PostMapping("/prospect") public Object prospect(Object b){ return null; }
                }
                """);
    }

    @Test
    void dynamicDestResolvesUnderTheSgCountryScope(@TempDir Path dir) throws Exception {
        writeFramework(dir);
        TraceResponse r = new RouteTraceService(dir.toString())
                .trace(new TraceRequest("prospect", "9.14", null, null, "SG"));

        assertThat(r.getFlow()).contains("R9.14_prospectRoute", "R9.14_prospectDetails");
        assertThat(r.getBackendApis()).contains("/bfs/prospect");
        assertThat(r.getWarnings()).noneMatch(w -> w.startsWith("Unresolved dynamic target"));
        assertThat(r.getWarnings()).noneMatch(w -> w.startsWith("Route not found"));
    }

    @Test
    void dynamicDestResolvesEvenWhenNotImportedByTheCountryBootstrap(@TempDir Path dir) throws Exception {
        // SG.xml refs sgContext but does NOT <import> common/dest.xml — the dest is a shared route
        // (de-duplicated across countries) loaded globally, not via SG's bootstrap. The trace must
        // still follow it (traversal runs on the full index), even though it isn't in SG's closure.
        Files.writeString(dir.resolve("SG.xml"),
                "<beans xmlns=\"http://www.springframework.org/schema/beans\">"
                        + "<camelContext id=\"camelContext\" xmlns=\"http://camel.apache.org/schema/spring\">"
                        + "<routeContextRef ref=\"sgContext\"/></camelContext></beans>");
        Files.createDirectories(dir.resolve("sg"));
        Files.writeString(dir.resolve("sg/entry.xml"),
                "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                        + "<routeContext id=\"sgContext\">"
                        + "<route id=\"R9.14_prospectRoute\">"
                        + "<from uri=\"direct:R9.14_prospect\"/>"
                        + "<setProperty name=\"DEST_ROUTE\"><constant>prospectDetails</constant></setProperty>"
                        + "<toD uri=\"direct:${exchangeProperty[FINAL_ROUTE_NAME]}\"/>"
                        + "</route></routeContext></beans:beans>");
        Files.createDirectories(dir.resolve("common"));
        Files.writeString(dir.resolve("common/dest.xml"),
                "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                        + "<routeContext id=\"commonContext\">"
                        + "<route id=\"R9.14_prospectDetails\">"
                        + "<from uri=\"direct:R9.14_prospectDetails\"/>"
                        + "<setProperty name=\"api\"><simple>/bfs/prospect</simple></setProperty>"
                        + "</route></routeContext></beans:beans>");
        Files.writeString(dir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @CommandHandler @PostMapping("/prospect") public Object prospect(Object b){ return null; }
                }
                """);
        TraceResponse r = new RouteTraceService(dir.toString())
                .trace(new TraceRequest("prospect", "9.14", null, null, "SG"));

        assertThat(r.getFlow()).contains("R9.14_prospectRoute", "R9.14_prospectDetails");
        assertThat(r.getBackendApis()).contains("/bfs/prospect");
        assertThat(r.getWarnings()).noneMatch(w -> w.startsWith("Unresolved dynamic target"));
    }
}

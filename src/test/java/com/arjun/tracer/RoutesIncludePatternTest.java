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
 * A repo whose routes are booted purely by a camel {@code routes-include-pattern} in
 * {@code application.yml} and whose route files use Camel's native {@code <routes>} DSL (no
 * {@code <camelContext>}/{@code <routeContext>}). The application config is the source of truth for
 * what loads: the pattern is resolved to files and loaded, {@code <country>} only substitutes dynamic
 * filenames, and a country-less file (shared {@code routes.xml}, a glob, a literal list) always loads.
 */
class RoutesIncludePatternTest {

    /** Camel native <routes> DSL — no <camelContext>, no <routeContext>. */
    private static String routesDsl(String routeId, String backend) {
        return "<routes xmlns=\"http://camel.apache.org/schema/spring\">"
                + "<route id=\"" + routeId + "\">"
                + "<from uri=\"direct:" + routeId + "\"/>"
                + "<setProperty name=\"api\"><simple>" + backend + "</simple></setProperty>"
                + "</route></routes>";
    }

    private static TraceResponse trace(RouteTraceService service, String op, String country) {
        return service.trace(new TraceRequest(op, "", null, null, country));
    }

    @Test
    void placeholderScopesToCountryAndSharedFileAlwaysLoads(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("routes-MY.xml"), routesDsl("myFlow", "/bfs/my"));
        Files.writeString(dir.resolve("routes-SG.xml"), routesDsl("sgFlow", "/bfs/sg"));
        Files.writeString(dir.resolve("routes.xml"), routesDsl("sharedFlow", "/bfs/shared"));
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), """
                camel:
                  main:
                    routes-include-pattern: classpath:routes-${country}.xml, classpath:routes.xml
                """);
        RouteTraceService service = new RouteTraceService(dir.toString());

        assertThat(service.listCountries(new TraceRequest(null, null, null, null)))
                .containsExactlyInAnyOrder("MY", "SG");

        // The ${country} file for MY is in scope.
        assertThat(trace(service, "myFlow", "MY").getFlow()).contains("myFlow");
        // The shared routes.xml (listed alongside the placeholder) loads for MY — the fix.
        assertThat(trace(service, "sharedFlow", "MY").getFlow()).contains("sharedFlow");
        // SG's file is another country's — NOT in MY's scope.
        assertThat(trace(service, "sgFlow", "MY").getWarnings())
                .anyMatch(w -> w.startsWith("Route not found"));
    }

    @Test
    void literalListWithoutPlaceholderLoadsEveryListedFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("routes-my.xml"), routesDsl("myFlow", "/bfs/my"));
        Files.writeString(dir.resolve("routes-sg.xml"), routesDsl("sgFlow", "/bfs/sg"));
        Files.createDirectories(dir.resolve("config"));
        // No ${country}, no profile — the config lists exact files and is the source of truth.
        Files.writeString(dir.resolve("config/application.yml"),
                "camel.main.routes-include-pattern=classpath:routes-sg.xml, classpath:routes-my.xml\n");
        RouteTraceService service = new RouteTraceService(dir.toString());

        // Every listed file loads for the typed country (controller-country scopes the DISPLAYED APIs).
        assertThat(trace(service, "myFlow", "MY").getFlow()).contains("myFlow");
        assertThat(trace(service, "sgFlow", "MY").getFlow()).contains("sgFlow");
    }

    @Test
    void globLoadsEveryMatchedFile(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("routes"));
        Files.writeString(dir.resolve("routes/a.xml"), routesDsl("aFlow", "/bfs/a"));
        Files.writeString(dir.resolve("routes/b.xml"), routesDsl("bFlow", "/bfs/b"));
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), """
                camel:
                  main:
                    routes-include-pattern: classpath:routes/*.xml
                """);
        RouteTraceService service = new RouteTraceService(dir.toString());

        assertThat(trace(service, "aFlow", "MY").getFlow()).contains("aFlow");
        assertThat(trace(service, "bFlow", "MY").getFlow()).contains("bFlow");
    }
}

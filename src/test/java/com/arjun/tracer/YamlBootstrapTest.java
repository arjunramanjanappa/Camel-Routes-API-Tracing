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
 * A repo whose country bootstraps are NOT named SG.xml/MY.xml but loaded via a camel
 * {@code routes-include-pattern} in application.yml — either a {@code ${country}} placeholder that
 * resolves to {@code secure-<country>.xml}, or an {@code application-<profile>.yml} whose profile is
 * the country. Used only when the filename way finds no bootstrap. Country matching is
 * case-insensitive (the value is typed in the UI).
 */
class YamlBootstrapTest {

    private static String routeFile(String routeId, String backend) {
        return "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                + "<routeContext id=\"" + routeId + "Ctx\">"
                + "<route id=\"" + routeId + "\">"
                + "<from uri=\"direct:" + routeId + "\"/>"
                + "<setProperty name=\"api\"><simple>" + backend + "</simple></setProperty>"
                + "</route></routeContext></beans:beans>";
    }

    private static final String CONTROLLER = """
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class Endpoints {
                @CommandHandler @PostMapping("/my") public Object myOnly(Object b){ return null; }
                @CommandHandler @PostMapping("/sg") public Object sgOnly(Object b){ return null; }
            }
            """;

    private void writeRoutes(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("routes"));
        Files.writeString(dir.resolve("routes/secure-my.xml"), routeFile("R9.14_myOnly", "/bfs/my"));
        Files.writeString(dir.resolve("routes/secure-sg.xml"), routeFile("R9.14_sgOnly", "/bfs/sg"));
        Files.writeString(dir.resolve("Endpoints.java"), CONTROLLER);
    }

    @Test
    void placeholderInApplicationYmlDiscoversPerCountryRouteFiles(@TempDir Path dir) throws Exception {
        writeRoutes(dir);
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), """
                camel:
                  main:
                    routes-include-pattern: classpath:routes/secure-${country:}.xml
                """);
        RouteTraceService service = new RouteTraceService(dir.toString());

        // Countries discovered from the files that match the placeholder (case-insensitive lookup).
        assertThat(service.listCountries(new TraceRequest(null, null, null, null)))
                .containsExactlyInAnyOrder("my", "sg");

        // MY scope resolves MY's route; SG's route is out of scope.
        TraceResponse my = service.trace(new TraceRequest("myOnly", "9.14", null, null, "MY"));
        assertThat(my.getFlow()).contains("R9.14_myOnly");
        assertThat(my.getBackendApis()).contains("/bfs/my");
        assertThat(service.trace(new TraceRequest("sgOnly", "9.14", null, null, "MY")).getWarnings())
                .anyMatch(w -> w.startsWith("Route not found"));
    }

    @Test
    void profileNamedApplicationYmlUsesTheProfileAsTheCountry(@TempDir Path dir) throws Exception {
        writeRoutes(dir);
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application-my.yml"),
                "camel.main.routes-include-pattern=classpath:routes/secure-my.xml\n");
        Files.writeString(dir.resolve("config/application-sg.yml"),
                "camel.main.routes-include-pattern=classpath:routes/secure-sg.xml\n");
        RouteTraceService service = new RouteTraceService(dir.toString());

        assertThat(service.listCountries(new TraceRequest(null, null, null, null)))
                .containsExactlyInAnyOrder("my", "sg");

        TraceResponse sg = service.trace(new TraceRequest("sgOnly", "9.14", null, null, "sg"));
        assertThat(sg.getFlow()).contains("R9.14_sgOnly");
        assertThat(sg.getBackendApis()).contains("/bfs/sg");
    }

    @Test
    void wildcardFolderPlusSharedFilePerProfile(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("routes/sg"));
        Files.createDirectories(dir.resolve("routes/my"));
        Files.writeString(dir.resolve("routes/sg/a.xml"), routeFile("R9.14_sgA", "/bfs/sga"));
        Files.writeString(dir.resolve("routes/sg/b.xml"), routeFile("R9.14_sgB", "/bfs/sgb"));
        Files.writeString(dir.resolve("routes/my/c.xml"), routeFile("R9.14_myC", "/bfs/myc"));
        Files.writeString(dir.resolve("routes/routes.xml"), routeFile("R9.14_shared", "/bfs/shared"));
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application-sg.yml"),
                "camel.main.routes-include-pattern=classpath:routes/sg/*.xml, classpath:routes/routes.xml\n");
        Files.writeString(dir.resolve("config/application-my.yml"),
                "camel.main.routes-include-pattern=classpath:routes/my/*.xml, classpath:routes/routes.xml\n");
        RouteTraceService service = new RouteTraceService(dir.toString());

        assertThat(service.listCountries(new TraceRequest(null, null, null, null)))
                .containsExactlyInAnyOrder("my", "sg");

        // SG scope: BOTH files under routes/sg/ (a + b) and the shared routes.xml resolve; MY's does not.
        assertThat(service.trace(new TraceRequest("sgA", "9.14", null, null, "SG")).getFlow()).contains("R9.14_sgA");
        assertThat(service.trace(new TraceRequest("sgB", "9.14", null, null, "SG")).getFlow()).contains("R9.14_sgB");
        assertThat(service.trace(new TraceRequest("shared", "9.14", null, null, "SG")).getFlow()).contains("R9.14_shared");
        assertThat(service.trace(new TraceRequest("myC", "9.14", null, null, "SG")).getWarnings())
                .anyMatch(w -> w.startsWith("Route not found"));

        // The shared file is in MY's scope too; SG's own routes are not.
        assertThat(service.trace(new TraceRequest("shared", "9.14", null, null, "MY")).getFlow()).contains("R9.14_shared");
        assertThat(service.trace(new TraceRequest("sgA", "9.14", null, null, "MY")).getWarnings())
                .anyMatch(w -> w.startsWith("Route not found"));
    }
}

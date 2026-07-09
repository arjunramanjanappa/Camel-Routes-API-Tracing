package com.uob.tracer;

import com.uob.tracer.api.CatalogResponse;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.TraceResponse;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SG bootstrap {@code <import>}s a routes file that is NOT in the primary source (it ships in a
 * shared-routes library). Without a dependency source the import is unresolved and surfaces under
 * "needs review"; adding the library as a dependency source resolves it and the shared route joins
 * the flow — with the warning gone.
 */
class DependencySourceTest {

    private static String bootstrap(String ref, String importResource) {
        return "<beans xmlns=\"http://www.springframework.org/schema/beans\">"
                + "<import resource=\"" + importResource + "\"/>"
                + "<camelContext id=\"camelContext\" xmlns=\"http://camel.apache.org/schema/spring\">"
                + "<routeContextRef ref=\"" + ref + "\"/></camelContext></beans>";
    }

    private static String routeContext(String id, String routeId, String backend) {
        return "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                + "<routeContext id=\"" + id + "\">"
                + "<route id=\"" + routeId + "\">"
                + "<from uri=\"direct:" + routeId + "\"/>"
                + "<setProperty name=\"api\"><simple>" + backend + "</simple></setProperty>"
                + "<log message=\"x\"/></route></routeContext></beans:beans>";
    }

    /** A route that hands off to another route via {@code direct:} (rather than importing it). */
    private static String callerRouteContext(String id, String routeId, String directTarget) {
        return "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                + "<routeContext id=\"" + id + "\">"
                + "<route id=\"" + routeId + "\">"
                + "<from uri=\"direct:" + routeId + "\"/>"
                + "<to uri=\"direct:" + directTarget + "\"/></route></routeContext></beans:beans>";
    }

    /** Primary source: an SG bootstrap importing a shared file that lives only in the dependency. */
    private Path primary(Path dir) throws Exception {
        Files.writeString(dir.resolve("SG.xml"), bootstrap("sgContext", "classpath:shared/shared-routes.xml"));
        Files.createDirectories(dir.resolve("sg"));
        Files.writeString(dir.resolve("sg/sg-routes.xml"), routeContext("sgContext", "sgOnlyRoute", "{{baseUrl}}/sg"));
        Files.writeString(dir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @CommandHandler @PostMapping("/sg")     public Object sgOnlyRoute(Object b){ return null; }
                    @CommandHandler @PostMapping("/shared") public Object sharedRoute(Object b){ return null; }
                }
                """);
        return dir;
    }

    /** Dependency source: the shared-routes library that defines the imported file. */
    private Path dependency(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("shared"));
        Files.writeString(dir.resolve("shared/shared-routes.xml"),
                routeContext("sharedContext", "sharedRoute", "{{baseUrl}}/shared"));
        return dir;
    }

    @Test
    void withoutDependencyTheImportIsUnresolvedAndFlaggedForReview(@TempDir Path primaryDir) throws Exception {
        RouteTraceService service = new RouteTraceService(primary(primaryDir).toString());

        CatalogResponse sg = (CatalogResponse) service.analyze(
                new TraceRequest(null, null, null, null, "SG"));

        assertThat(sg.getWarnings()).anyMatch(w -> w.startsWith("Unresolved <import>"));
        assertThat(sg.getNeedsReview()).anyMatch(w -> w.startsWith("Unresolved <import>")
                && w.contains("shared/shared-routes.xml"));

        // The shared route isn't in the registry, so its API isn't wired into the SG scope.
        List<String> ops = sg.getGroups().stream()
                .flatMap(g -> g.traces().stream())
                .map(TraceResponse::getOperationName)
                .toList();
        assertThat(ops).doesNotContain("sharedRoute");
    }

    @Test
    void addingTheDependencyResolvesTheImportAndClearsTheReview(@TempDir Path primaryDir,
                                                                 @TempDir Path depDir) throws Exception {
        RouteTraceService service = new RouteTraceService(primary(primaryDir).toString());
        List<String> deps = List.of("local:" + dependency(depDir));

        CatalogResponse sg = (CatalogResponse) service.analyze(
                new TraceRequest(null, null, null, null, "SG", null, null, deps));

        assertThat(sg.getWarnings()).noneMatch(w -> w.startsWith("Unresolved <import>"));
        assertThat(sg.getNeedsReview()).isEmpty();

        List<String> ops = sg.getGroups().stream()
                .flatMap(g -> g.traces().stream())
                .map(TraceResponse::getOperationName)
                .toList();
        assertThat(ops).contains("sgOnlyRoute", "sharedRoute");

        // And the shared route now traces end to end through its backend.
        TraceResponse shared = service.trace(
                new TraceRequest("sharedRoute", null, null, null, "SG", null, null, deps));
        assertThat(shared.getFlow()).contains("sharedRoute");
        assertThat(shared.getBackendApis()).contains("{{baseUrl}}/shared");
        assertThat(shared.getWarnings()).noneMatch(w -> w.startsWith("Route not found"));
        assertThat(shared.getNeedsReview()).isEmpty();
    }

    /**
     * A host reached via {@code direct:} (not an {@code <import>}) lives in a dependency. Even under a
     * country scope — where only the bootstrap's import/ref closure is normally in scope — the
     * dependency's host route must resolve, because dependency routes are country-agnostic.
     */
    @Test
    void aHostCalledViaDirectFromADependencyResolvesUnderACountryScope(@TempDir Path primaryDir,
                                                                        @TempDir Path depDir) throws Exception {
        // Primary: SG bootstrap wires sgOnlyRoute, which calls direct:hostCall — but does NOT import it.
        Files.writeString(primaryDir.resolve("SG.xml"),
                "<beans xmlns=\"http://www.springframework.org/schema/beans\">"
                        + "<camelContext id=\"camelContext\" xmlns=\"http://camel.apache.org/schema/spring\">"
                        + "<routeContextRef ref=\"sgContext\"/></camelContext></beans>");
        Files.createDirectories(primaryDir.resolve("sg"));
        Files.writeString(primaryDir.resolve("sg/sg-routes.xml"),
                callerRouteContext("sgContext", "sgOnlyRoute", "hostCall"));
        Files.writeString(primaryDir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @CommandHandler @PostMapping("/sg") public Object sgOnlyRoute(Object b){ return null; }
                }
                """);
        // Dependency: the core host route, reached only via direct: (no import from the bootstrap).
        Files.createDirectories(depDir.resolve("core"));
        Files.writeString(depDir.resolve("core/host.xml"),
                routeContext("coreContext", "hostCall", "{{baseUrl}}/host"));

        RouteTraceService service = new RouteTraceService(primaryDir.toString());

        // Without the dependency, the host is unresolved under the SG scope.
        TraceResponse noDep = service.trace(new TraceRequest("sgOnlyRoute", null, null, null, "SG"));
        assertThat(noDep.getWarnings()).anyMatch(w -> w.startsWith("Route not found"));

        // With the dependency, the host resolves even though it is not imported by the bootstrap.
        List<String> deps = List.of("local:" + depDir);
        TraceResponse withDep = service.trace(
                new TraceRequest("sgOnlyRoute", null, null, null, "SG", null, null, deps));
        assertThat(withDep.getFlow()).contains("sgOnlyRoute", "hostCall");
        assertThat(withDep.getBackendApis()).contains("{{baseUrl}}/host");
        assertThat(withDep.getWarnings()).noneMatch(w -> w.startsWith("Route not found"));
        assertThat(withDep.getNeedsReview()).isEmpty();
    }

    /**
     * A dependency that cannot be loaded (bad Bitbucket URL / auth) must not be swallowed — the
     * reason is surfaced for review, so the user can see WHY an import is still unresolved instead
     * of a dependency that silently did nothing. The primary analysis still completes.
     */
    @Test
    void aDependencyThatFailsToLoadIsSurfacedNotSwallowed(@TempDir Path primaryDir) throws Exception {
        RouteTraceService service = new RouteTraceService(primary(primaryDir).toString());
        List<String> deps = List.of("bit:file:///no/such/repo.git|master");   // cannot be cloned

        CatalogResponse sg = (CatalogResponse) service.analyze(
                new TraceRequest(null, null, null, null, "SG", null, null, deps));

        assertThat(sg.getNeedsReview()).anyMatch(w -> w.startsWith("Dependency source could not be loaded"));
        // the original unresolved import is still reported — the failed dependency didn't hide it
        assertThat(sg.getNeedsReview()).anyMatch(w -> w.startsWith("Unresolved <import>"));
    }

    @Test
    void aLocalDependencyPathThatDoesNotExistIsSurfaced(@TempDir Path primaryDir) throws Exception {
        RouteTraceService service = new RouteTraceService(primary(primaryDir).toString());
        List<String> deps = List.of("local:" + primaryDir.resolve("does-not-exist"));

        CatalogResponse sg = (CatalogResponse) service.analyze(
                new TraceRequest(null, null, null, null, "SG", null, null, deps));

        assertThat(sg.getNeedsReview()).anyMatch(w -> w.startsWith("Dependency path not found"));
    }
}

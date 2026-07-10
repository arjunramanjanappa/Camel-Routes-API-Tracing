package com.uob.tracer;

import com.uob.tracer.api.CatalogResponse;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.TraceResponse;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On Release Scope, N/A resolves each API to its own latest (else base) route — different versions
 * per API. Rather than splitting the catalog into one panel per version (9.3 / 9.2 / BASE …), N/A
 * consolidates every API into ONE group, the same single-list shape a specific version shows. Each
 * entry still carries its resolved route, so the per-API version is visible per row.
 */
class CatalogNaGroupingTest {

    private static String route(String id, String backend) {
        return "<route id=\"" + id + "\"><from uri=\"direct:" + id + "\"/>"
                + "<setProperty name=\"api\"><simple>" + backend + "</simple></setProperty></route>";
    }

    private static final String ROUTES = "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
            + "<routeContext id=\"ctx\">"
            + route("R9.1_foo", "/bfs/foo") + route("R9.3_foo", "/bfs/foo")
            + route("R9.2_bar", "/bfs/bar") + route("R9.3_qux", "/bfs/qux")
            + route("baz", "/bfs/baz")
            + "</routeContext></beans:beans>";

    private static final String CONTROLLER = """
            package com.x;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class C {
                @PostMapping("/foo") public Object foo(Object b){ return null; }
                @PostMapping("/bar") public Object bar(Object b){ return null; }
                @PostMapping("/qux") public Object qux(Object b){ return null; }
                @PostMapping("/baz") public Object baz(Object b){ return null; }
            }
            """;

    private CatalogResponse catalog(Path dir, String version) throws Exception {
        return (CatalogResponse) new RouteTraceService(dir.toString())
                .analyze(new TraceRequest(null, version, null, null));
    }

    @Test
    void naConsolidatesEveryApiIntoOneGroup(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("r.xml"), ROUTES);
        Files.writeString(dir.resolve("C.java"), CONTROLLER);

        CatalogResponse na = catalog(dir, "N/A");
        assertThat(na.getVersionsFound()).containsExactly("N/A");   // one group, not 9.3/9.2/BASE
        assertThat(na.getGroups()).hasSize(1);
        assertThat(na.getGroups().get(0).traces())
                .extracting(TraceResponse::getApi)
                .containsExactlyInAnyOrder("/foo", "/bar", "/qux", "/baz");
        // Each row still shows its own resolved route (so the version is visible per API).
        assertThat(na.getGroups().get(0).traces())
                .extracting(TraceResponse::getResolvedRoute)
                .containsExactlyInAnyOrder("R9.3_foo", "R9.2_bar", "R9.3_qux", "baz");

        // A specific version is unchanged — one group for that version.
        assertThat(catalog(dir, "9.3").getVersionsFound()).containsExactly("9.3");
    }
}

package com.uob.tracer;

import com.uob.tracer.api.ApiDiff;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.VersionDiffReport;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N/A on Release Impact is not a diff — there is no prior release to compare. It is a snapshot of the
 * latest (else base) route each in-scope API resolves to: a versioned API shows its highest R&lt;ver&gt;_
 * route, a base-only API shows BASE. Nothing is marked changed/new/unchanged.
 */
class ReleaseImpactSnapshotTest {

    private static String route(String id, String backend) {
        return "<route id=\"" + id + "\"><from uri=\"direct:" + id + "\"/>"
                + "<setProperty name=\"api\"><simple>" + backend + "</simple></setProperty></route>";
    }

    private static final String ROUTES = "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
            + "<routeContext id=\"ctx\">"
            + route("R9.1_foo", "/bfs/foo")
            + route("R9.3_foo", "/bfs/foo")
            + route("bar", "/bfs/bar")
            + "</routeContext></beans:beans>";

    private static final String CONTROLLER = """
            package com.x;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class C {
                @PostMapping("/foo") public Object foo(Object b){ return null; }
                @PostMapping("/bar") public Object bar(Object b){ return null; }
            }
            """;

    private static ApiDiff api(VersionDiffReport r, String path) {
        return r.getApis().stream().filter(a -> a.api().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void naReturnsALatestOrBaseSnapshotNotADiff(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("r.xml"), ROUTES);
        Files.writeString(dir.resolve("C.java"), CONTROLLER);
        VersionDiffReport r = new RouteTraceService(dir.toString())
                .versionDiff(new TraceRequest(null, "N/A", null, null));

        assertThat(r.isSnapshot()).isTrue();
        assertThat(r.getChangedCount()).isZero();
        assertThat(r.getNewCount()).isZero();
        assertThat(r.getUnchangedCount()).isZero();
        assertThat(r.getSnapshotCount()).isEqualTo(2);
        assertThat(r.getApis()).allMatch(a -> a.status().equals(ApiDiff.SNAPSHOT));

        // Versioned API → its highest route + version.
        assertThat(api(r, "/foo").targetRoute()).isEqualTo("R9.3_foo");
        assertThat(api(r, "/foo").targetVersion()).isEqualTo("9.3");
        // Base-only API → the base route, labelled BASE.
        assertThat(api(r, "/bar").targetRoute()).isEqualTo("bar");
        assertThat(api(r, "/bar").targetVersion()).isEqualTo("BASE");
    }
}

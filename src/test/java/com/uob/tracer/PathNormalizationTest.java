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
 * The Source directory accepts a path typed/pasted in either Windows ({@code C:\...}) or
 * macOS/Linux ({@code /Users/...}) style: surrounding quotes ("Copy as path") are stripped and
 * either slash direction resolves on the host OS.
 */
class PathNormalizationTest {

    private static final String ROUTES = "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
            + "<routeContext id=\"ctx\"><route id=\"foo\"><from uri=\"direct:foo\"/>"
            + "<setProperty name=\"api\"><simple>/bfs/foo</simple></setProperty></route></routeContext></beans:beans>";
    private static final String CONTROLLER = """
            package com.x;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class C { @PostMapping("/foo") public Object foo(Object b){ return null; } }
            """;

    private static void write(Path dir) throws Exception {
        Files.writeString(dir.resolve("r.xml"), ROUTES);
        Files.writeString(dir.resolve("C.java"), CONTROLLER);
    }

    /** APIs discovered when the source dir is given as {@code sourceDir} (via the request). */
    private static List<String> apis(Path dir, String sourceDir) {
        CatalogResponse cat = (CatalogResponse) new RouteTraceService(dir.toString())
                .analyze(new TraceRequest(null, "N/A", null, sourceDir));
        return cat.getGroups().stream().flatMap(g -> g.traces().stream()).map(TraceResponse::getApi).toList();
    }

    @Test
    void plainNativePathResolves(@TempDir Path dir) throws Exception {
        write(dir);
        assertThat(apis(dir, dir.toString())).contains("/foo");
    }

    @Test
    void quotedPathResolves(@TempDir Path dir) throws Exception {
        write(dir);
        assertThat(apis(dir, "\"" + dir + "\"")).contains("/foo");   // Windows "Copy as path" style
    }

    @Test
    void eitherSlashStyleResolvesOnTheHost(@TempDir Path dir) throws Exception {
        write(dir);
        assertThat(apis(dir, dir.toString().replace('\\', '/'))).contains("/foo");   // forward slashes
        assertThat(apis(dir, dir.toString().replace('/', '\\'))).contains("/foo");   // back slashes
    }

    @Test
    void quotedForwardSlashPathResolves(@TempDir Path dir) throws Exception {
        write(dir);
        // Combined: quoted AND forward-slashed (a common paste on either OS).
        assertThat(apis(dir, "\"" + dir.toString().replace('\\', '/') + "\"")).contains("/foo");
    }
}

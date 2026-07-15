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
 * Foundation for grouping a multi-module analysis: every response carries the module's name
 * (its own pom.xml {@code artifactId}, not the parent's) and, for a repo with no versioned
 * {@code R<ver>_} routes (e.g. the SPL-Secure base routes), a concrete release version is
 * analysed at N/A so its APIs are returned rather than excluded.
 */
class ModuleFoundationTest {

    private static void writeUnversionedRepo(Path dir, String artifactId) throws Exception {
        Files.writeString(dir.resolve("pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                        + "<parent><groupId>com.uob</groupId><artifactId>platform-parent</artifactId><version>1</version></parent>"
                        + "<artifactId>" + artifactId + "</artifactId></project>");
        Files.writeString(dir.resolve("r.xml"),
                "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\"><routeContext id=\"c\">"
                        + "<route id=\"foo\"><from uri=\"direct:foo\"/><setProperty name=\"api\"><simple>/bfs/foo</simple></setProperty></route>"
                        + "</routeContext></beans:beans>");
        Files.writeString(dir.resolve("C.java"),
                "package com.x; import org.springframework.web.bind.annotation.*;"
                        + " @RestController public class C { @PostMapping(\"/foo\") public Object foo(Object b){ return null; } }");
    }

    @Test
    void moduleNameFromPomAndUnversionedRepoAnalysedAtNa(@TempDir Path dir) throws Exception {
        writeUnversionedRepo(dir, "spl-secure");

        // A concrete release version on an unversioned repo would normally exclude every API.
        CatalogResponse cat = (CatalogResponse) new RouteTraceService(dir.toString())
                .analyze(new TraceRequest(null, "9.18", null, null));

        assertThat(cat.getModuleName()).isEqualTo("spl-secure");     // pom artifactId, NOT the parent's
        assertThat(cat.isUnversioned()).isTrue();                    // no R<ver>_ routes → N/A
        assertThat(cat.getVersionsFound()).containsExactly("N/A");   // one group, labelled N/A
        assertThat(cat.getGroups().stream().flatMap(g -> g.traces().stream()).map(TraceResponse::getApi))
                .contains("/foo");                                   // APIs returned, not excluded
    }

    @Test
    void versionedRepoIsNotFlaggedUnversioned(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><artifactId>mighty</artifactId></project>");
        Files.writeString(dir.resolve("r.xml"),
                "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\"><routeContext id=\"c\">"
                        + "<route id=\"R9.18_foo\"><from uri=\"direct:R9.18_foo\"/><setProperty name=\"api\"><simple>/bfs/foo</simple></setProperty></route>"
                        + "</routeContext></beans:beans>");
        Files.writeString(dir.resolve("C.java"),
                "package com.x; import org.springframework.web.bind.annotation.*;"
                        + " @RestController public class C { @PostMapping(\"/foo\") public Object foo(Object b){ return null; } }");

        CatalogResponse cat = (CatalogResponse) new RouteTraceService(dir.toString())
                .analyze(new TraceRequest(null, "9.18", null, null));

        assertThat(cat.getModuleName()).isEqualTo("mighty");
        assertThat(cat.isUnversioned()).isFalse();
        assertThat(cat.getVersionsFound()).containsExactly("9.18");
    }
}

package com.uob.tracer;

import com.uob.tracer.api.ApiDiff;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.VersionDiffReport;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-country leakage guard. A dependency source carries another country's versioned route
 * ({@code security-th-v1.xml} with {@code R6.0_validate}). Dependency files are wholesale-included in every
 * country scope so a {@code direct:} host resolves without an import — but that must NOT make another
 * country's versioned API a version of THIS country's API. For an SG diff at 9.14, {@code /validate}'s
 * immediate-lower must not resolve to the TH dependency's {@code R6.0_validate}.
 */
class CrossCountryDependencyScopeTest {

    private static String routeCtx(String id, String routeId, String backend) {
        return "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                + "<routeContext id=\"" + id + "\">"
                + "<route id=\"" + routeId + "\">"
                + "<from uri=\"direct:" + routeId + "\"/>"
                + "<setProperty name=\"api\"><simple>" + backend + "</simple></setProperty>"
                + "<log message=\"x\"/></route></routeContext></beans:beans>";
    }

    /** Primary SG source: SG.xml wires its own security context (R9.14_validate) via routeContextRef. */
    private Path primary(Path dir) throws Exception {
        Files.writeString(dir.resolve("SG.xml"),
                "<beans xmlns=\"http://www.springframework.org/schema/beans\">"
                        + "<camelContext id=\"camelContext\" xmlns=\"http://camel.apache.org/schema/spring\">"
                        + "<routeContextRef ref=\"sgSecurityContext\"/></camelContext></beans>");
        Files.createDirectories(dir.resolve("sg"));
        Files.writeString(dir.resolve("sg/security-sg-v1.xml"),
                routeCtx("sgSecurityContext", "R9.14_validate", "{{baseUrl}}/sg/public/validate"));
        Files.writeString(dir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @CommandHandler @PostMapping("/validate") public Object validate(Object b){ return null; }
                }
                """);
        return dir;
    }

    /** Dependency source: TH's versioned security route — NOT referenced by SG.xml. */
    private Path dependency(Path dir) throws Exception {
        Files.writeString(dir.resolve("security-th-v1.xml"),
                routeCtx("thSecurityContext", "R6.0_validate", "{{baseUrl}}/th/public/validate"));
        return dir;
    }

    @Test
    void thDependencyVersionIsNotTheSgImmediateLower(@TempDir Path primaryDir, @TempDir Path depDir) throws Exception {
        RouteTraceService service = new RouteTraceService(primary(primaryDir).toString());
        List<String> deps = List.of("local:" + dependency(depDir));

        VersionDiffReport report = service.versionDiff(
                new TraceRequest(null, "9.14", null, null, "SG", null, null, deps, null, null));

        ApiDiff validate = report.getApis().stream()
                .filter(a -> "validate".equals(a.operation()))
                .findFirst().orElseThrow(() -> new AssertionError("validate API not in the SG diff"));

        // The 9.14 route is SG's own; the TH dependency's R6.0 must not be treated as its predecessor.
        assertThat(validate.targetRoute()).isEqualTo("R9.14_validate");
        assertThat(validate.lowerRoute()).isNotEqualTo("R6.0_validate");
        assertThat(validate.lowerVersion()).isNotEqualTo("6.0");
        // With no in-scope lower version, it's a NEW API for SG (nothing below 9.14 in SG's own scope).
        assertThat(validate.status()).isEqualTo(ApiDiff.NEW);
    }
}

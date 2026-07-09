package com.arjun.tracer;

import com.arjun.tracer.api.CatalogResponse;
import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "spl-secure" flavour: a {@code RestEndpointRouteAspect} intercepts every UFW call and forces it
 * through a fixed {@code direct:redirectRoute} that dispatches by the CONTROLLER CLASS name —
 * {@code <toD uri="direct:send${header.operationName}Route"/>} where
 * {@code operationName = target.getClass().getSimpleName()} — to a route named
 * {@code send<ControllerClass>Route}. Countries load via {@code secure-${country}.xml}; routes use the
 * native {@code <routes>} DSL.
 *
 * <p>Resolution is gated on the dispatcher marker (auto-detected from the source) AND the
 * {@code send<ControllerClass>Route} existing; otherwise it falls back to the method-name rule, so
 * Mighty/SPL/BAU are unaffected.
 */
class CommandDispatchTest {

    private static String route(String id, String backend) {
        return "<route id=\"" + id + "\"><from uri=\"direct:" + id + "\"/>"
                + "<setProperty name=\"api\"><simple>" + backend + "</simple></setProperty></route>";
    }

    /** The fixed dispatcher (the marker) + this country's routes, in the native <routes> DSL. */
    private static String secureRoutes(String... routeXml) {
        StringBuilder sb = new StringBuilder("<routes>"
                + "<route id=\"redirectRoute\"><from uri=\"direct:redirectRoute\"/>"
                + "<toD uri=\"direct:send${header.operationName}Route\"/></route>");
        for (String r : routeXml) {
            sb.append(r);
        }
        return sb.append("</routes>").toString();
    }

    /** One controller class per command, named after the command (so send<Class>Route is the route). */
    private static String controller(String className, String path, String method) {
        return "package com.x.secure;\n"
                + "import org.springframework.web.bind.annotation.*;\n"
                + "@RestController @RequestMapping(\"/services\")\n"
                + "public class " + className + " {\n"
                + "  @CommandHandler @PostMapping(\"" + path + "\")\n"
                + "  public Object " + method + "(Object b){ return null; }\n"
                + "}\n";
    }

    private static TraceResponse api(List<TraceResponse> traces, String path) {
        return traces.stream().filter(t -> path.equals(t.getApi())).findFirst().orElse(null);
    }

    @Test
    void resolvesByControllerClassNameAndScopesByCountry(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("routes"));
        // MY: the class-named dispatch route + a plain method-named route (fallback case). No SgOnly route.
        Files.writeString(dir.resolve("routes/secure-MY.xml"), secureRoutes(
                route("sendValidateNotificationCommandRoute", "/bfs/validate"),
                route("legacyMethod", "/bfs/legacy")));
        // SG has the SgOnly class route — must NOT leak into MY.
        Files.writeString(dir.resolve("routes/secure-SG.xml"), secureRoutes(
                route("sendSgOnlyRoute", "/bfs/sg")));
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), """
                camel:
                  main:
                    routes-include-pattern: classpath:routes/secure-${country:}.xml
                """);
        Files.writeString(dir.resolve("ValidateNotificationCommand.java"),
                controller("ValidateNotificationCommand", "/public/get/push", "handle"));
        Files.writeString(dir.resolve("LegacyBau.java"),
                controller("LegacyBau", "/bau/legacy", "legacyMethod"));  // no sendLegacyBauRoute → fallback
        Files.writeString(dir.resolve("SgOnly.java"),
                controller("SgOnly", "/sg/only", "handle"));
        RouteTraceService service = new RouteTraceService(dir.toString());

        CatalogResponse cat = (CatalogResponse) service.analyze(new TraceRequest(null, "N/A", null, null, "MY"));
        List<TraceResponse> traces = cat.getGroups().stream().flatMap(g -> g.traces().stream()).toList();

        // Dispatch: the op resolves to send<ControllerClass>Route (class ValidateNotificationCommand).
        TraceResponse validate = api(traces, "/services/public/get/push");
        assertThat(validate).isNotNull();
        assertThat(validate.getResolvedRoute()).isEqualTo("sendValidateNotificationCommandRoute");
        assertThat(validate.getBackendApis()).contains("/bfs/validate");

        // Fallback: class LegacyBau has no sendLegacyBauRoute, so it resolves by the method name.
        TraceResponse legacy = api(traces, "/services/bau/legacy");
        assertThat(legacy).isNotNull();
        assertThat(legacy.getResolvedRoute()).isEqualTo("legacyMethod");

        // Country isolation: SgOnly's route lives only in secure-SG.xml, so it is NOT in MY's view.
        assertThat(api(traces, "/services/sg/only")).isNull();
    }

    @Test
    void withoutTheDispatcherMarkerResolvesByMethodNameEvenIfSendRouteExists(@TempDir Path dir) throws Exception {
        // No redirectRoute / send${...}Route dispatcher anywhere → NOT the spl-secure flavour. A route that
        // happens to be named sendFooRoute must be ignored; resolution stays method-name (Mighty/SPL rule).
        Files.createDirectories(dir.resolve("routes"));
        Files.writeString(dir.resolve("routes/secure-MY.xml"),
                "<routes>"
                        + route("sendFooRoute", "/bfs/foo-dispatch")   // a lookalike, but no dispatcher present
                        + route("bar", "/bfs/bar")
                        + "</routes>");
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), """
                camel:
                  main:
                    routes-include-pattern: classpath:routes/secure-${country:}.xml
                """);
        Files.writeString(dir.resolve("Foo.java"),
                "package com.x.legacy;\n"
                        + "import org.springframework.web.bind.annotation.*;\n"
                        + "@RestController @RequestMapping(\"/x\")\n"
                        + "public class Foo {\n"
                        + "  @CommandHandler @PostMapping(\"/bar\") public Object bar(Object b){ return null; }\n"
                        + "}\n");
        RouteTraceService service = new RouteTraceService(dir.toString());

        TraceResponse r = service.trace(new TraceRequest("/x/bar", "N/A", null, null, "MY"));
        assertThat(r.getResolvedRoute()).isEqualTo("bar");          // method name, NOT sendFooRoute
        assertThat(r.getBackendApis()).contains("/bfs/bar");
    }
}

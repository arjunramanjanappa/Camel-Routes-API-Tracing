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
 * The intercepted-UFW ("SPL-Secure") flavour, now folded into SPL and <b>auto-detected</b>: a
 * {@code RestEndpointRouteAspect} forces every UFW call through a fixed {@code direct:redirectRoute}
 * that dispatches by {@code <toD uri="direct:send${header.operationName}Route"/>}, where
 * {@code operationName} is the {@code @CommandHandler} command (primary) or the handler method name
 * (fallback). So the entry route is {@code send<command>Route}, else {@code send<method>Route}.
 *
 * <p>The dispatcher route ({@code direct:redirectRoute} / a {@code send${…}Route} toD) is the marker
 * that auto-selects this resolver. A repo without it keeps the plain method-name rule, so Mighty and
 * regular SPL are unaffected — no app selection needed.
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

    private static final String CONTROLLER = """
            package com.x.secure;
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/services")
            public class PublicApiController {
                @CommandHandler(command="ValidateNotificationCommand") @PostMapping("/public/get/push")
                public Object validateNotification(Object b){ return null; }

                @CommandHandler @PostMapping("/enquiry")
                public Object enquiry(Object b){ return null; }          // bare marker → method-name fallback

                @CommandHandler(command="SgOnlyCommand") @PostMapping("/sg/only")
                public Object sgOnly(Object b){ return null; }
            }
            """;

    private static TraceResponse api(List<TraceResponse> traces, String path) {
        return traces.stream().filter(t -> path.equals(t.getApi())).findFirst().orElse(null);
    }

    private static List<TraceResponse> catalogTraces(RouteTraceService service, String country) {
        CatalogResponse cat = (CatalogResponse) service.analyze(
                new TraceRequest(null, "N/A", null, null, country));   // no app — resolution is auto-detected
        return cat.getGroups().stream().flatMap(g -> g.traces().stream()).toList();
    }

    @Test
    void autoDetectedDispatchResolvesByCommandThenMethodAndScopesByCountry(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("routes"));
        Files.writeString(dir.resolve("routes/secure-MY.xml"), secureRoutes(
                route("sendValidateNotificationCommandRoute", "/bfs/validate"),
                route("sendenquiryRoute", "/bfs/enquiry")));
        Files.writeString(dir.resolve("routes/secure-SG.xml"), secureRoutes(
                route("sendSgOnlyCommandRoute", "/bfs/sg")));
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), """
                camel:
                  main:
                    routes-include-pattern: classpath:routes/secure-${country:}.xml
                """);
        Files.writeString(dir.resolve("PublicApiController.java"), CONTROLLER);
        RouteTraceService service = new RouteTraceService(dir.toString());

        List<TraceResponse> traces = catalogTraces(service, "MY");

        // Primary: @CommandHandler command → send<command>Route.
        TraceResponse validate = api(traces, "/services/public/get/push");
        assertThat(validate).isNotNull();
        assertThat(validate.getResolvedRoute()).isEqualTo("sendValidateNotificationCommandRoute");
        assertThat(validate.getBackendApis()).contains("/bfs/validate");

        // Fallback: bare @CommandHandler (no command) → send<methodName>Route.
        TraceResponse enquiry = api(traces, "/services/enquiry");
        assertThat(enquiry).isNotNull();
        assertThat(enquiry.getResolvedRoute()).isEqualTo("sendenquiryRoute");

        // Country isolation: SgOnly's route is only in secure-SG.xml → not in MY's view.
        assertThat(api(traces, "/services/sg/only")).isNull();
    }

    @Test
    void withoutTheDispatcherMarkerResolvesByMethodNameEvenIfSendRouteExists(@TempDir Path dir) throws Exception {
        // No redirectRoute / send${...}Route dispatcher → NOT the SPL-Secure flavour. A lookalike
        // sendFooRoute must be ignored; resolution stays method-name (plain Mighty/SPL rule).
        Files.createDirectories(dir.resolve("routes"));
        Files.writeString(dir.resolve("routes/secure-MY.xml"),
                "<routes>"
                        + route("sendFooRoute", "/bfs/foo-dispatch")
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

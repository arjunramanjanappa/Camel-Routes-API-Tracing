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
 * The <b>SPL-Secure</b> application: a {@code RestEndpointRouteAspect} intercepts every UFW call and
 * forces it through a fixed {@code direct:redirectRoute} that dispatches by
 * {@code <toD uri="direct:send${header.operationName}Route"/>}, where {@code operationName} is the
 * {@code @CommandHandler} command (primary) or the handler method name (fallback). So the entry route
 * is {@code send<command>Route}, else {@code send<method>Route}. All countries' APIs live in the same
 * controller; scope is purely which {@code send…Route} routes are in {@code secure-<country>.xml}.
 *
 * <p>Applied ONLY when the SPL-Secure app is selected — Mighty/SPL/BAU never enter this path.
 */
class CommandDispatchTest {

    private static final String APP = "SPL-Secure";

    private static String route(String id, String backend) {
        return "<route id=\"" + id + "\"><from uri=\"direct:" + id + "\"/>"
                + "<setProperty name=\"api\"><simple>" + backend + "</simple></setProperty></route>";
    }

    private static String secureRoutes(String... routeXml) {
        StringBuilder sb = new StringBuilder("<routes>"
                + "<route id=\"redirectRoute\"><from uri=\"direct:redirectRoute\"/>"
                + "<toD uri=\"direct:send${header.operationName}Route\"/></route>");
        for (String r : routeXml) {
            sb.append(r);
        }
        return sb.append("</routes>").toString();
    }

    /** One shared controller carrying every country's UFW endpoints. */
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

    private RouteTraceService secureRepo(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("routes"));
        // MY: the command route + a method-named route (fallback). No SgOnly route.
        Files.writeString(dir.resolve("routes/secure-MY.xml"), secureRoutes(
                route("sendValidateNotificationCommandRoute", "/bfs/validate"),
                route("sendenquiryRoute", "/bfs/enquiry")));
        // SG: has the SgOnly command route only — must NOT leak into MY.
        Files.writeString(dir.resolve("routes/secure-SG.xml"), secureRoutes(
                route("sendSgOnlyCommandRoute", "/bfs/sg")));
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), """
                camel:
                  main:
                    routes-include-pattern: classpath:routes/secure-${country:}.xml
                """);
        Files.writeString(dir.resolve("PublicApiController.java"), CONTROLLER);
        return new RouteTraceService(dir.toString());
    }

    /** Catalog request for a country in a given app flavour. */
    private static TraceRequest catalog(String country, String app) {
        return new TraceRequest(null, "N/A", null, null, country, null, null, List.of(), app);
    }

    private static TraceResponse api(List<TraceResponse> traces, String path) {
        return traces.stream().filter(t -> path.equals(t.getApi())).findFirst().orElse(null);
    }

    private static List<TraceResponse> catalogTraces(RouteTraceService service, String country, String app) {
        CatalogResponse cat = (CatalogResponse) service.analyze(catalog(country, app));
        return cat.getGroups().stream().flatMap(g -> g.traces().stream()).toList();
    }

    @Test
    void splSecureResolvesByCommandThenMethodAndScopesByCountry(@TempDir Path dir) throws Exception {
        RouteTraceService service = secureRepo(dir);
        List<TraceResponse> traces = catalogTraces(service, "MY", APP);

        // Primary: the @CommandHandler command → send<command>Route.
        TraceResponse validate = api(traces, "/services/public/get/push");
        assertThat(validate).isNotNull();
        assertThat(validate.getResolvedRoute()).isEqualTo("sendValidateNotificationCommandRoute");
        assertThat(validate.getBackendApis()).contains("/bfs/validate");

        // Fallback: bare @CommandHandler (no command) → send<methodName>Route.
        TraceResponse enquiry = api(traces, "/services/enquiry");
        assertThat(enquiry).isNotNull();
        assertThat(enquiry.getResolvedRoute()).isEqualTo("sendenquiryRoute");

        // Country isolation: SgOnly's route is only in secure-SG.xml, so it is NOT in MY's view.
        assertThat(api(traces, "/services/sg/only")).isNull();
    }

    @Test
    void withoutSplSecureAppTheSendRoutesAreNotUsed(@TempDir Path dir) throws Exception {
        RouteTraceService service = secureRepo(dir);
        // Same repo, but the SPL app (not SPL-Secure): resolution stays method-name, which matches no
        // route here (routes are send<…>Route), so none of these endpoints land in scope.
        List<TraceResponse> traces = catalogTraces(service, "MY", "SPL");

        assertThat(api(traces, "/services/public/get/push")).isNull();
        assertThat(api(traces, "/services/enquiry")).isNull();
    }
}

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
 * The "SPL-secure" framework flavour: every UFW call is intercepted through a fixed {@code redirectRoute}
 * that dispatches by command — {@code <toD uri="direct:send${header.operationName}Route"/>} — to a route
 * named {@code send<command>Route}. The operation is identified by the {@code @CommandHandler} command,
 * not the method name, and the countries load via {@code routes-include-pattern: secure-${country}.xml}.
 *
 * <p>Resolution auto-detects this: when an operation carries a command AND {@code send<command>Route}
 * exists in the scoped source, the op resolves there; otherwise it falls back to the method-name rule
 * (Mighty/SPL/BAU) unchanged. Route files use Camel's native {@code <routes>} DSL.
 */
class CommandDispatchTest {

    private static String route(String id, String backend) {
        return "<route id=\"" + id + "\"><from uri=\"direct:" + id + "\"/>"
                + "<setProperty name=\"api\"><simple>" + backend + "</simple></setProperty></route>";
    }

    /** The fixed dispatcher + this country's command routes, in the native <routes> DSL. */
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
            @RestController
            @RequestMapping("/services")
            public class PublicApiController {
                @CommandHandler(command="ValidateNotificationCommand", validator="Getvalidate")
                @PostMapping("/public/get/push")
                public Object validateNotification(Object b){ return null; }

                @CommandHandler(command="MissingCommand")
                @PostMapping("/bau")
                public Object missingHandler(Object b){ return null; }

                @CommandHandler(command="SgOnlyCommand")
                @PostMapping("/sg/only")
                public Object sgOnly(Object b){ return null; }
            }
            """;

    private RouteTraceService secureRepo(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("routes"));
        // MY: the validate command route + a method-named route (for the fallback case). No SgOnly route.
        Files.writeString(dir.resolve("routes/secure-MY.xml"), secureRoutes(
                route("sendValidateNotificationCommandRoute", "/bfs/validate"),
                route("missingHandler", "/bfs/bau")));
        // SG: has the SgOnly command route — so it must NOT leak into MY's scope.
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

    private static TraceResponse api(List<TraceResponse> traces, String path) {
        return traces.stream().filter(t -> path.equals(t.getApi())).findFirst().orElse(null);
    }

    @Test
    void commandDispatchResolvesToSendCommandRouteAndScopesByCountry(@TempDir Path dir) throws Exception {
        RouteTraceService service = secureRepo(dir);

        assertThat(service.listCountries(new TraceRequest(null, null, null, null)))
                .containsExactlyInAnyOrder("MY", "SG");

        CatalogResponse cat = (CatalogResponse) service.analyze(new TraceRequest(null, "N/A", null, null, "MY"));
        List<TraceResponse> traces = cat.getGroups().stream().flatMap(g -> g.traces().stream()).toList();

        // The @CommandHandler op resolves to send<command>Route (not the method name) and is in MY's scope.
        TraceResponse validate = api(traces, "/services/public/get/push");
        assertThat(validate).isNotNull();
        assertThat(validate.getResolvedRoute()).isEqualTo("sendValidateNotificationCommandRoute");
        assertThat(validate.getFlow()).contains("sendValidateNotificationCommandRoute");
        assertThat(validate.getBackendApis()).contains("/bfs/validate");

        // Fallback: a command with no send<command>Route resolves by the method name (Mighty/SPL rule).
        TraceResponse missing = api(traces, "/services/bau");
        assertThat(missing).isNotNull();
        assertThat(missing.getResolvedRoute()).isEqualTo("missingHandler");

        // Country isolation: SgOnly's route lives only in secure-SG.xml, so it is NOT in MY's view.
        assertThat(api(traces, "/services/sg/only")).isNull();
    }

    @Test
    void singleTraceByPathFollowsTheCommandDispatch(@TempDir Path dir) throws Exception {
        RouteTraceService service = secureRepo(dir);

        TraceResponse r = service.trace(
                new TraceRequest("/services/public/get/push", "N/A", null, null, "MY"));
        assertThat(r.getResolvedRoute()).isEqualTo("sendValidateNotificationCommandRoute");
        assertThat(r.getBackendApis()).contains("/bfs/validate");
    }
}

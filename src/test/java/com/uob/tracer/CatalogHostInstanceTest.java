package com.uob.tracer;

import com.uob.tracer.api.CatalogResponse;
import com.uob.tracer.api.RouteGraph;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In the catalog graph (all APIs in one graph), several APIs share one CamelHttpUri host route
 * ({@code callNtnRoute}), each handing it a DIFFERENT backend. Each API must get its OWN host
 * instance so selecting one API doesn't highlight every other API's backend. Regression for the
 * per-call instance id colliding across APIs (the counter reset per API).
 */
class CatalogHostInstanceTest {

    private static final String ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="ctx">
                <route id="getDelete"><from uri="direct:getDelete"/>
                  <setProperty name="api"><simple>/ntn/delete</simple></setProperty>
                  <to uri="direct:callNtnRoute"/></route>
                <route id="getFetch"><from uri="direct:getFetch"/>
                  <setProperty name="api"><simple>/ntn/fetch</simple></setProperty>
                  <to uri="direct:callNtnRoute"/></route>
                <route id="getPut"><from uri="direct:getPut"/>
                  <setProperty name="api"><simple>/ntn/put</simple></setProperty>
                  <to uri="direct:callNtnRoute"/></route>
                <route id="callNtnRoute"><from uri="direct:callNtnRoute"/>
                  <setHeader name="CamelHttpUri"><simple>${exchangeProperty.api}</simple></setHeader>
                  <toD uri="${header.CamelHttpUri}"/></route>
              </routeContext>
            </beans:beans>
            """;

    private static final String CONTROLLER = """
            package com.x;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class C {
                @PostMapping("/get/delete") public Object getDelete(Object b){ return null; }
                @PostMapping("/get/fetch")  public Object getFetch(Object b){ return null; }
                @PostMapping("/get/put")    public Object getPut(Object b){ return null; }
            }
            """;

    /** The host instance node that a given caller route points to. */
    private static String hostInstanceOf(RouteGraph g, String callerRoute) {
        return g.getEdges().stream()
                .filter(e -> e.from().equals("route:" + callerRoute) && e.to().startsWith("route:callNtnRoute#"))
                .map(e -> e.to()).findFirst().orElseThrow();
    }

    private static List<String> backendsFrom(RouteGraph g, String node) {
        return g.getEdges().stream()
                .filter(e -> e.from().equals(node) && e.to().startsWith("backend:"))
                .map(e -> e.to()).sorted().toList();
    }

    @Test
    void eachApiGetsItsOwnHostInstanceInTheCatalog(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("r.xml"), ROUTES);
        Files.writeString(dir.resolve("C.java"), CONTROLLER);
        CatalogResponse cat = (CatalogResponse) new RouteTraceService(dir.toString())
                .analyze(new TraceRequest(null, "", null, null));
        RouteGraph g = cat.getGraph();

        // Each caller's host instance must be distinct...
        String di = hostInstanceOf(g, "getDelete");
        String fe = hostInstanceOf(g, "getFetch");
        String pu = hostInstanceOf(g, "getPut");
        assertThat(List.of(di, fe, pu)).doesNotHaveDuplicates();

        // ...and carry ONLY that API's backend (not all three).
        assertThat(backendsFrom(g, di)).containsExactly("backend:/ntn/delete");
        assertThat(backendsFrom(g, fe)).containsExactly("backend:/ntn/fetch");
        assertThat(backendsFrom(g, pu)).containsExactly("backend:/ntn/put");
    }
}

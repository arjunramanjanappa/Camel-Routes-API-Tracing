package com.uob.tracer;

import com.uob.tracer.loader.CamelRouteModelLoader;
import com.uob.tracer.model.RouteElement;
import com.uob.tracer.model.RouteModel;
import com.uob.tracer.model.SetPropertyElement;
import com.uob.tracer.model.ToElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the Camel RouteDefinition loader handles routes wrapped in
 * Spring {@code <beans>} / {@code <routeContext>} (the legacy camel-spring-xml
 * shape) by unwrapping them — i.e. the spec-preferred path is used, not the DOM
 * fallback.
 */
class CamelRouteModelLoaderTest {

    private final CamelRouteModelLoader loader = new CamelRouteModelLoader();

    /** Mirrors the reported file: routes inside <beans> -> <routeContext>, no inner namespace. */
    private static final String BEANS_ROUTE_CONTEXT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="qwe">
                <route id="redirectRoute">
                  <from uri="direct:redirectRoute"/>
                  <to uri="bean:redirectRouteProcessor"/>
                  <to uri="direct:callLCM"/>
                </route>
                <route id="callLCM">
                  <from uri="direct:callLCM"/>
                  <setProperty name="api"><simple>{{baseUrl}}/lcm/submit</simple></setProperty>
                  <log message="calling lcm"/>
                </route>
              </routeContext>
            </beans:beans>
            """;

    @Test
    void unwrapsBeansRouteContextAndLoadsViaCamel() throws Exception {
        List<RouteModel> routes = loader.load("softoken-XX.xml", BEANS_ROUTE_CONTEXT);

        assertThat(routes).hasSize(2);
        assertThat(routes).allSatisfy(r -> assertThat(r.source()).isEqualTo("camel"));

        RouteModel redirect = routes.stream()
                .filter(r -> "redirectRoute".equals(r.routeId())).findFirst().orElseThrow();
        assertThat(redirect.fromUri()).isEqualTo("direct:redirectRoute");
        assertThat(toUris(redirect)).contains("direct:callLCM");

        RouteModel callLcm = routes.stream()
                .filter(r -> "callLCM".equals(r.routeId())).findFirst().orElseThrow();
        assertThat(callLcm.elements())
                .filteredOn(e -> e instanceof SetPropertyElement)
                .extracting(e -> ((SetPropertyElement) e).value())
                .contains("{{baseUrl}}/lcm/submit");
    }

    private List<String> toUris(RouteModel route) {
        return route.elements().stream()
                .filter(e -> e instanceof ToElement)
                .map(e -> ((ToElement) e).uri())
                .toList();
    }
}

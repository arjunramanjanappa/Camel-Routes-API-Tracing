package com.uob.tracer;

import com.uob.tracer.loader.RouteRegistry;
import com.uob.tracer.model.RouteModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Camel property placeholders in route names are resolved from the source properties, so a hop like
 * {@code direct:logoutversion{{sso.version}}} finds the literal {@code logoutversionV5} route when
 * {@code sso.version=V5} is defined in application.properties.
 */
class RouteRegistryPlaceholderTest {

    private static RouteModel route(String name) {
        return new RouteModel(name, "direct:" + name, List.of(), "dom");
    }

    @Test
    void resolvesPropertyPlaceholderInARouteReference() {
        RouteRegistry registry = new RouteRegistry(Map.of("sso.version", "V5"));
        RouteModel target = route("logoutversionV5");
        registry.add(target);

        assertThat(registry.lookup("logoutversion{{sso.version}}")).isSameAs(target);
        assertThat(registry.contains("logoutversion{{sso.version}}")).isTrue();
        assertThat(registry.resolveName("logoutversion{{sso.version}}")).isEqualTo("logoutversionV5");
    }

    @Test
    void unknownPlaceholderIsLeftLiteralSoItDoesNotSilentlyMismatch() {
        RouteRegistry registry = new RouteRegistry(Map.of("sso.version", "V5"));
        registry.add(route("logoutversionV5"));

        // No value for {{other}} and no default → stays literal → no route matches (surfaces as needs-review).
        assertThat(registry.resolveName("logoutversion{{other}}")).isEqualTo("logoutversion{{other}}");
        assertThat(registry.lookup("logoutversion{{other}}")).isNull();
    }

    @Test
    void usesTheDefaultWhenTheKeyIsUnknown() {
        RouteRegistry registry = new RouteRegistry(Map.of());
        assertThat(registry.resolveName("logoutversion{{sso.version:V1}}")).isEqualTo("logoutversionV1");
    }

    @Test
    void alsoMatchesWhenTheRouteItselfIsDefinedWithThePlaceholder() {
        RouteRegistry registry = new RouteRegistry(Map.of("sso.version", "V5"));
        RouteModel target = route("logoutversion{{sso.version}}");   // the route's own from-name carries it
        registry.add(target);

        assertThat(registry.lookup("logoutversionV5")).isSameAs(target);
        assertThat(registry.lookup("logoutversion{{sso.version}}")).isSameAs(target);
    }
}

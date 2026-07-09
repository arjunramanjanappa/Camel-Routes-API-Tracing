package com.uob.tracer;

import com.uob.tracer.loader.RouteRegistry;
import com.uob.tracer.model.RouteModel;
import com.uob.tracer.resolve.VersionResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Version comparison must handle multi-part (patch) versions like 9.18.1, not just
 * two-part majors: 9.18.1 &gt; 9.18, 9.18.1 &gt; 9.18.0, and 9.18 == 9.18.0.
 */
class VersionResolverTest {

    private final VersionResolver resolver = new VersionResolver();

    private RouteRegistry registryWith(String... routeIds) {
        RouteRegistry registry = new RouteRegistry();
        for (String id : routeIds) {
            registry.add(new RouteModel(id, "direct:" + id, List.of(), "dom"));
        }
        return registry;
    }

    @Test
    void resolvesAndOrdersThreePartVersions() {
        RouteRegistry reg = registryWith("R9.14_xApi", "R9.18_xApi", "R9.18.1_xApi");

        // The patch version resolves to its own exact route.
        assertThat(resolver.resolve(reg, "xApi", "9.18.1").version()).isEqualTo("9.18.1");

        // 9.18.1 > 9.18 > 9.14 → immediate-lower walks one step, not to the major.
        assertThat(resolver.immediateLowerVersion(reg, "xApi", "9.18.1")).isEqualTo("9.18");
        assertThat(resolver.immediateLowerVersion(reg, "xApi", "9.18")).isEqualTo("9.14");

        // A 9.18 client must NOT pick up 9.18.1 (it's strictly greater), and a request
        // above everything falls back to the highest available patch.
        assertThat(resolver.resolve(reg, "xApi", "9.18").version()).isEqualTo("9.18");
        assertThat(resolver.resolve(reg, "xApi", "9.18.2").version()).isEqualTo("9.18.1");
    }

    @Test
    void anUnversionedRouteIsBothBaseAndLatest() {
        // Only an un-versioned route exists (no R<ver>_ prefix), e.g. getFundTransfer.
        RouteRegistry reg = registryWith("getFundTransfer");

        // Any requested version falls back to it (it is the floor)…
        assertThat(resolver.resolve(reg, "getFundTransfer", "9.18").routeName()).isEqualTo("getFundTransfer");
        assertThat(resolver.resolve(reg, "getFundTransfer", "9.18").baseFallback()).isTrue();
        // …a blank version yields it too, and there is no lower version below it. So the
        // single un-versioned route serves as both the base AND the latest for every client.
        assertThat(resolver.resolve(reg, "getFundTransfer", "").routeName()).isEqualTo("getFundTransfer");
        assertThat(resolver.immediateLowerVersion(reg, "getFundTransfer", "9.18")).isNull();
    }

    @Test
    void anUnversionedBaseSitsBelowEveryVersionedRoute() {
        // A base route alongside versioned ones is the baseline under the lowest release.
        RouteRegistry reg = registryWith("getFundTransfer", "R9.4_getFundTransfer", "R9.18_getFundTransfer");

        assertThat(resolver.resolve(reg, "getFundTransfer", "9.18").version()).isEqualTo("9.18");
        // immediate-lower of the lowest versioned release is null (the base is handled by
        // the caller via registry.contains, not returned here).
        assertThat(resolver.immediateLowerVersion(reg, "getFundTransfer", "9.4")).isNull();
        // A request below the lowest versioned route falls back to the un-versioned base.
        assertThat(resolver.resolve(reg, "getFundTransfer", "9.0").baseFallback()).isTrue();
    }

    @Test
    void treatsTrailingZeroAsEqual() {
        // 9.18 == 9.18.0: a 9.18 client resolves to an R9.18.0 route, and a 9.18.1
        // request still sees 9.18.0 as its immediate-lower.
        RouteRegistry reg = registryWith("R9.18.0_xApi", "R9.18.1_xApi");

        assertThat(resolver.resolve(reg, "xApi", "9.18").version()).isEqualTo("9.18.0");
        assertThat(resolver.immediateLowerVersion(reg, "xApi", "9.18.1")).isEqualTo("9.18.0");
    }
}

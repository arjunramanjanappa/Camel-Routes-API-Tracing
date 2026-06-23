package com.arjun.tracer;

import com.arjun.tracer.resolve.OperationInfo;
import com.arjun.tracer.resolve.OperationResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The interface/impl mapping split must not produce duplicate operations. */
class OperationResolverTest {

    @Test
    void interfaceAndImplMappingSplitDeduplicatesToTheQualifiedPath() {
        OperationResolver r = new OperationResolver();
        // The API interface declares the method mapping, with no class-level prefix.
        r.addSource("""
            import org.springframework.web.bind.annotation.PostMapping;
            public interface FxApi {
                @PostMapping("/fx/getFxRate")
                Object getFxRate(Object body);
            }
            """);
        // The impl class adds @RequestMapping("/services/sg") and re-declares the mapping.
        r.addSource("""
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            @RequestMapping("/services/sg")
            public class FxController implements FxApi {
                @PostMapping("/fx/getFxRate")
                public Object getFxRate(Object body) { return null; }
            }
            """);

        // Both files parse a getFxRate handler, but all() collapses the unqualified
        // "/fx/getFxRate" into the fully-qualified "/services/sg/fx/getFxRate".
        assertThat(r.all()).hasSize(1);
        assertThat(r.all()).extracting(OperationInfo::path).containsExactly("/services/sg/fx/getFxRate");
        assertThat(r.all().get(0).operationName()).isEqualTo("getFxRate");
    }

    @Test
    void onlyUfwCommandHandlerEndpointsAreKeptWhenTheCodebaseHasThem() {
        OperationResolver r = new OperationResolver();
        // OLD BAU controller (JAX-RS markers + Spring mapping, NO @CommandHandler) — ignored.
        r.addSource("""
            import org.springframework.web.bind.annotation.*;
            import javax.ws.rs.*;
            @RestController
            @RequestMapping("services/sg")
            @Path("services/sg")
            @Produces("application/json")
            public class ProductEndpoint {
                @POST
                @Path("/getProduct")
                @Consumes("application/json")
                @PostMapping("/getProduct")
                public Object getProduct(Object body) { return null; }
            }
            """);
        // An UNRELATED legacy endpoint (different api, no @CommandHandler) — also ignored.
        r.addSource("""
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("legacy")
            public class StatusEndpoint {
                @GetMapping("/status")
                public Object legacyStatus(Object body) { return null; }
            }
            """);
        // The migrated UFW controller (implements an interface, bare @CommandHandler marker).
        r.addSource("""
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("services/sg")
            public class ProductApiController implements ProductApi {
                @CommandHandler
                @PostMapping("/getProduct")
                public Object getProduct(Object body) { return null; }
            }
            """);

        // Every non-@CommandHandler endpoint is ignored once any UFW endpoint exists.
        assertThat(r.all()).hasSize(1);
        OperationInfo op = r.all().get(0);
        assertThat(op.operationName()).isEqualTo("getProduct");
        assertThat(op.path()).isEqualTo("/services/sg/getProduct");
        assertThat(op.commandHandler()).isTrue();
        assertThat(op.controllerType()).isEqualTo("ProductApiController");
    }

    @Test
    void withNoCommandHandlerAnywhereEveryEndpointIsKept() {
        OperationResolver r = new OperationResolver();
        // A pre-UFW codebase (no @CommandHandler at all) must still list its APIs.
        r.addSource("""
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("services/sg")
            public class ReportEndpoint {
                @GetMapping("/report")
                public Object report(Object body) { return null; }
            }
            """);

        assertThat(r.all()).hasSize(1);
        assertThat(r.all().get(0).path()).isEqualTo("/services/sg/report");
        assertThat(r.all().get(0).commandHandler()).isFalse();
    }
}

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
    void legacyBauEndpointIsDroppedWhenAUfwCommandHandlerTwinExistsAtTheSamePath() {
        OperationResolver r = new OperationResolver();
        // OLD BAU controller — JAX-RS markers + Spring mapping, but NO @CommandHandler.
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
        // NEW UFW controller — implements an interface, carries a bare @CommandHandler marker.
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

        // Both declare /services/sg/getProduct; only the UFW (@CommandHandler) one survives.
        assertThat(r.all()).hasSize(1);
        OperationInfo op = r.all().get(0);
        assertThat(op.path()).isEqualTo("/services/sg/getProduct");
        assertThat(op.commandHandler()).isTrue();
        assertThat(op.controllerType()).isEqualTo("ProductApiController");
    }

    @Test
    void legacyBauEndpointIsDroppedWhenAUfwTwinSharesTheOperationEvenAtADifferentPath() {
        OperationResolver r = new OperationResolver();
        // OLD BAU controller — its JAX-RS-side prefix puts it at a DIFFERENT url, but it's
        // the same handler/operation (getProduct) and has no @CommandHandler.
        r.addSource("""
            import org.springframework.web.bind.annotation.*;
            import javax.ws.rs.*;
            @RestController
            @RequestMapping("legacy/product")
            public class ProductEndpoint {
                @POST
                @PostMapping("/getProduct")
                public Object getProduct(Object body) { return null; }
            }
            """);
        // NEW UFW controller — same operation, @CommandHandler, different url prefix.
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

        // Different paths, same operation → the legacy one is still dropped.
        assertThat(r.all()).hasSize(1);
        OperationInfo op = r.all().get(0);
        assertThat(op.operationName()).isEqualTo("getProduct");
        assertThat(op.path()).isEqualTo("/services/sg/getProduct");
        assertThat(op.commandHandler()).isTrue();
    }

    @Test
    void aLegacyEndpointWithNoUfwTwinIsKept() {
        OperationResolver r = new OperationResolver();
        // No @CommandHandler anywhere for this path → Option A keeps it (don't over-drop).
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

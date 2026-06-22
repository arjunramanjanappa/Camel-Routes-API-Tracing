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
}

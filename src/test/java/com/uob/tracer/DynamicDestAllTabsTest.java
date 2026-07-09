package com.uob.tracer;

import com.uob.tracer.api.CatalogResponse;
import com.uob.tracer.api.ImpactIndex;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.TraceResponse;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A dynamic {@code <toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>} must populate the
 * graph/analysis on ALL THREE tabs, not just Trace/View-flow — each tab feeds the traversal through
 * a different entry point (catalog, impact index, version diff). Here we verify Release Scope
 * (catalog) and Release Test (impact); the Release Impact (diff) path is covered by
 * {@link DynamicDestRouteDiffTest}.
 */
class DynamicDestAllTabsTest {

    private static final String ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="c">
                <route id="R9.4_prospectRoute">
                  <from uri="direct:R9.4_prospect"/>
                  <setProperty name="DEST_ROUTE"><constant>prospectDetails</constant></setProperty>
                  <toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>
                </route>
                <route id="R9.3_prospectRoute">
                  <from uri="direct:R9.3_prospect"/>
                  <setProperty name="DEST_ROUTE"><constant>prospectDetails</constant></setProperty>
                  <toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>
                </route>
                <route id="R9.4_prospectDetails">
                  <from uri="direct:R9.4_prospectDetails"/>
                  <setProperty name="api"><simple>/bfs/prospect/v4</simple></setProperty>
                </route>
                <route id="R9.3_prospectDetails">
                  <from uri="direct:R9.3_prospectDetails"/>
                  <setProperty name="api"><simple>/bfs/prospect/v3</simple></setProperty>
                </route>
              </routeContext>
            </beans:beans>
            """;

    private RouteTraceService service(Path dir) throws Exception {
        Files.writeString(dir.resolve("routes.xml"), ROUTES);
        Files.writeString(dir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @CommandHandler @PostMapping("/prospect") public Object prospect(Object b){ return null; }
                }
                """);
        return new RouteTraceService(dir.toString());
    }

    @Test
    void releaseScopeCatalogPopulatesTheDynamicDestRouteAndBackend(@TempDir Path dir) throws Exception {
        CatalogResponse cat = (CatalogResponse) service(dir).analyze(new TraceRequest(null, "9.4", null, null));

        TraceResponse prospect = cat.getGroups().stream()
                .flatMap(g -> g.traces().stream())
                .filter(t -> "prospect".equals(t.getOperationName()))
                .findFirst().orElseThrow();
        assertThat(prospect.getFlow()).contains("R9.4_prospectRoute", "R9.4_prospectDetails");
        assertThat(prospect.getBackendApis()).contains("/bfs/prospect/v4");
        // The graph the tab renders carries the dynamic dest's backend node.
        assertThat(cat.getGraph().getNodes()).anyMatch(n -> n.id().equals("backend:/bfs/prospect/v4"));
        assertThat(cat.getNeedsReview()).noneMatch(w -> w.startsWith("Unresolved dynamic target"));
    }

    @Test
    void releaseScopeCatalogWithNoVersionResolvesEachGroupAtItsOwnVersion(@TempDir Path dir) throws Exception {
        CatalogResponse cat = (CatalogResponse) service(dir).analyze(new TraceRequest(null, null, null, null));

        // The 9.4 group resolves its dynamic dest to R9.4_prospectDetails, the 9.3 group to R9.3.
        assertThat(cat.getGraph().getNodes()).anyMatch(n -> n.id().equals("backend:/bfs/prospect/v4"));
        assertThat(cat.getGraph().getNodes()).anyMatch(n -> n.id().equals("backend:/bfs/prospect/v3"));
        assertThat(cat.getNeedsReview()).noneMatch(w -> w.startsWith("Unresolved dynamic target"));
    }

    @Test
    void releaseTestImpactIndexPopulatesTheDynamicDestBackend(@TempDir Path dir) throws Exception {
        ImpactIndex idx = service(dir).impactIndex(new TraceRequest(null, "9.4", null, null));

        assertThat(idx.getAllBackends()).contains("/bfs/prospect/v4");
        assertThat(idx.getApis()).anyMatch(a -> a.backends().contains("/bfs/prospect/v4"));
        assertThat(idx.getNeedsReview()).noneMatch(w -> w.startsWith("Unresolved dynamic target"));
    }
}

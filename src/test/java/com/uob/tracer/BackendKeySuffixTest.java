package com.uob.tracer;

import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.TraceResponse;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backend API and host-URL properties are matched by SUFFIX (case-insensitive), not an exact
 * name, so every repo's naming is covered: {@code name="api"} or anything ending in "api"
 * ({@code backendApi}, {@code targetApi}), and {@code name="hosturl"} or anything ending in "hosturl".
 */
class BackendKeySuffixTest {

    private static final String ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="ctx">
                <route id="R9.5_getRate">
                  <from uri="direct:R9.5_getRate"/>
                  <setProperty name="callHosturl"><simple>http://svc/rate</simple></setProperty>
                  <setProperty name="backendApi"><simple>/bfs/rate</simple></setProperty>
                  <to uri="direct:callHost"/>
                </route>
                <route id="callHost">
                  <from uri="direct:callHost"/>
                  <setHeader name="CamelHttpUri"><simple>${exchangeProperty.api}</simple></setHeader>
                  <toD uri="${header.CamelHttpUri}"/>
                </route>
              </routeContext>
            </beans:beans>
            """;

    @Test
    void backendApiAndHostUrlAreMatchedBySuffix(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("r.xml"), ROUTES);
        TraceResponse r = new RouteTraceService(dir.toString())
                .trace(new TraceRequest("R9.5_getRate", "", null, null));

        // "backendApi" (ends with api) is recognised as the backend.
        assertThat(r.getBackendApis()).containsExactly("/bfs/rate");
        // "callHosturl" (ends with hosturl) is recognised as this backend's host URL.
        assertThat(r.getBackendHosturls()).containsEntry("/bfs/rate", "http://svc/rate");
    }
}

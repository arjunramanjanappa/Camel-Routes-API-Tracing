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
 * The SSO failover pattern: {@code callSSOv5route} sets the request headers, then a {@code <choice>}
 * whose branches each wrap a {@code <loadBalance>} that forwards to a primary ({@code callssov5P}) or
 * secondary ({@code callssov5S}) host route — the same logical backend under a different URL. These are
 * load-balanced/failover alternatives, so the trace must show ONE host (the primary), not two hosts each
 * expecting its own backend log response.
 */
class LoadBalanceHostTest {

    private static final String ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="ssoCtx">
                <route id="R5.3_authenticateusingSSOv5Route">
                  <from uri="direct:R5.3_authenticateusingSSOv5Route"/>
                  <to uri="direct:callSSOv5route"/>
                </route>
                <route id="callSSOv5route">
                  <from uri="direct:callSSOv5route"/>
                  <setHeader name="CamelHttpPath"><constant>/rest/auth/login</constant></setHeader>
                  <setHeader name="CamelHttpMethod"><constant>POST</constant></setHeader>
                  <choice>
                    <when>
                      <simple>${header.primaryAvailable} == 'Y'</simple>
                      <loadBalance>
                        <failover/>
                        <to uri="direct:callssov5P"/>
                      </loadBalance>
                    </when>
                    <otherwise>
                      <loadBalance>
                        <failover/>
                        <to uri="direct:callssov5S"/>
                      </loadBalance>
                    </otherwise>
                  </choice>
                </route>
                <route id="callssov5P">
                  <from uri="direct:callssov5P"/>
                  <setProperty name="api"><constant>/rest/auth/login</constant></setProperty>
                  <setHeader name="CamelHttpUri"><simple>${exchangeProperty.api}</simple></setHeader>
                  <toD uri="${header.CamelHttpUri}"/>
                </route>
                <route id="callssov5S">
                  <from uri="direct:callssov5S"/>
                  <setProperty name="api"><constant>/rest/auth/login/secondary</constant></setProperty>
                  <setHeader name="CamelHttpUri"><simple>${exchangeProperty.api}</simple></setHeader>
                  <toD uri="${header.CamelHttpUri}"/>
                </route>
              </routeContext>
            </beans:beans>
            """;

    @Test
    void aChoiceOfLoadBalancersCollapsesToASingleHost(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("sso.xml"), ROUTES);
        TraceResponse r = new RouteTraceService(dir.toString())
                .trace(new TraceRequest("R5.3_authenticateusingSSOv5Route", "", null, null));

        // Only the primary host is traced; the secondary is a load-balanced alternative, not a second host.
        assertThat(r.getFlow()).contains("R5.3_authenticateusingSSOv5Route", "callSSOv5route", "callssov5P");
        assertThat(r.getFlow()).doesNotContain("callssov5S");

        // One backend log response expected (the primary), not two.
        assertThat(r.getBackendApis()).containsExactly("/rest/auth/login");
        assertThat(r.getBackendApis()).doesNotContain("/rest/auth/login/secondary");

        // No host node for the secondary.
        assertThat(r.getGraph().getNodes()).noneMatch(n -> n.id().startsWith("route:callssov5S"));
    }
}

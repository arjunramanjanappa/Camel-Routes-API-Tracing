package com.uob.tracer;

import com.uob.tracer.api.ApiLogResult;
import com.uob.tracer.api.BackendCallResult;
import com.uob.tracer.api.LogAnalysisReport;
import com.uob.tracer.api.LogStatus;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.service.LogAnalysisService;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One api (/bfs/payee/list) is reached via two routes that set the SAME api property but DIFFERENT hostUrls
 * (and versions): bpGetPayeelist -> /bfs/bp/payee/list @2.1 and R9.14_fetGetPayeeList -> /bfs/ft/payee/list
 * @3.2. The log line carries the hostUrl, not the api value. Each version's flow must match its OWN logged
 * hostUrl instead of both collapsing to the first-seen one. Here the log exercises only the ft path (@3.2),
 * so that flow is SUCCESS and the bp flow (@2.1) stays NOT_TESTED -> the api is PARTIAL.
 */
class MultiHostUrlPerApiTest {

    private final RouteTraceService svc = new RouteTraceService("src/test/resources/svc-multihost");

    private static final String CORR = "abcdef0123456789abcdef0123456789";

    /** Mighty flavour: [MightyMessage]/[MightyHostMessage], host path is the bare hostUrl. */
    private static final String MIGHTY_LOG =
        "2026-06-11 18.43.45.102 [t] INFO [MightyMessage][MTY][sess1][user1][9.14][" + CORR + "][IOS][]-/mty-banking/services/sg/payee/list -Request - {\"serviceRequest\":{}}\n"
      + "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][sess1][user1][9.14][" + CORR + "][IOS][230ms]-/bfs/ft/payee/list -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"3.2\"}}\n"
      + "2026-06-11 18.43.45.502 [t] INFO [MightyMessage][MTY][sess1][user1][9.14][" + CORR + "][IOS][500ms]-/mty-banking/services/sg/payee/list -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";

    /** SPL flavour: [SPLMessage]/[SPLHostMessage], paths carry the /mty-banking-01 prefix. */
    private static final String SPL_LOG =
        "2026-06-11 18.43.45.102 [t] INFO [SPLMessage][MTY][sess1][user1][9.14][" + CORR + "][IOS][]-/mty-banking-01/services/sg/payee/list -Request - {\"serviceRequest\":{}}\n"
      + "2026-06-11 18.43.45.318 [t] INFO [SPLHostMessage][MTY][sess1][user1][9.14][" + CORR + "][IOS][230ms]-/mty-banking-01/bfs/ft/payee/list -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"3.2\"}}\n"
      + "2026-06-11 18.43.45.502 [t] INFO [SPLMessage][MTY][sess1][user1][9.14][" + CORR + "][IOS][500ms]-/mty-banking-01/services/sg/payee/list -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";

    @Test
    void mightyLogMatchesEachVersionsOwnHostUrl() throws IOException {
        assertPerVersionCoverage(analyze(MIGHTY_LOG, "Mighty"));
    }

    @Test
    void splLogMatchesEachVersionsOwnHostUrl() throws IOException {
        assertPerVersionCoverage(analyze(SPL_LOG, "SPL"));
    }

    private ApiLogResult analyze(String log, String app) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8))) {
            LogAnalysisReport rep = new LogAnalysisService(svc).analyze(in, "log.log", "9.14", null,
                    "src/test/resources/svc-multihost", null, null, true, app);
            return rep.apis().stream().filter(a -> "getPayeeList".equals(a.operation()))
                    .findFirst().orElseThrow(() -> new AssertionError("getPayeeList not in report; apis="
                            + rep.apis().stream().map(ApiLogResult::operation).toList()));
        }
    }

    /** The ft flow (@3.2, hostUrl /bfs/ft/payee/list) is exercised by the log; the bp flow (@2.1,
     *  hostUrl /bfs/bp/payee/list) is not. Each version resolves to its OWN hostUrl for matching. */
    private static void assertPerVersionCoverage(ApiLogResult api) {
        List<BackendCallResult> backends = api.backends();

        BackendCallResult ft = versionRow(backends, "3.2");
        assertThat(ft.status()).as("ft flow @3.2 exercised by the log").isEqualTo(LogStatus.SUCCESS);
        assertThat(ft.observedPath()).as("ft flow matched its own logged hostUrl (verbatim path may carry an app prefix)")
                .endsWith("/bfs/ft/payee/list");

        BackendCallResult bp = versionRow(backends, "2.1");
        assertThat(bp.status()).as("bp flow @2.1 not exercised").isEqualTo(LogStatus.NOT_TESTED);
        assertThat(bp.observedPath()).as("bp flow saw no log call").isNull();

        // The bp hostUrl must NOT have collapsed onto the ft version (the 1:1 first-wins bug).
        assertThat(backends).extracting(BackendCallResult::expectedServiceVersion)
                .containsExactlyInAnyOrder("2.1", "3.2");
        assertThat(api.status()).as("one of two flows tested -> PARTIAL").isEqualTo(LogStatus.PARTIAL);
    }

    private static BackendCallResult versionRow(List<BackendCallResult> backends, String version) {
        return backends.stream().filter(b -> version.equals(b.expectedServiceVersion()))
                .findFirst().orElseThrow(() -> new AssertionError("no backend row for svc " + version
                        + "; rows=" + backends.stream().map(BackendCallResult::expectedServiceVersion).toList()));
    }
}

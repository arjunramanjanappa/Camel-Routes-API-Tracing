package com.uob.tracer;

import com.uob.tracer.api.ApiImpact;
import com.uob.tracer.api.ApiLogResult;
import com.uob.tracer.api.BackendCallResult;
import com.uob.tracer.api.ImpactIndex;
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
 * Two levels of {@code <choice>}: getRouteStatus reaches 4 backends — one unconditional (/myinfo) and three
 * across nested choice branches (/fetchsub, /getsub, /getbe). One transaction can only cover /myinfo plus one
 * branch, so a log with a single trace leaves two backends untested and the API MUST read PARTIAL, not SUCCESS.
 */
class NestedChoiceCoverageTest {

    private static final String DIR = "src/test/resources/nested-choice";

    private static String fe(String corr, String dir, String ms) {
        return "2026-06-11 18.43.45." + ms + " [t] INFO [MightyMessage][MTY][s][u][9.14][" + corr
                + "][IOS][" + ("Request".equals(dir) ? "" : "500ms") + "]-/get/route/status -" + dir
                + " - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";
    }

    private static String be(String corr, String path, String ms) {
        return "2026-06-11 18.43.45." + ms + " [t] INFO [MightyHostMessage][MTY][s][u][9.14][" + corr
                + "][IOS][30ms]-" + path + " -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";
    }

    private ApiLogResult analyze(String log) throws IOException {
        RouteTraceService svc = new RouteTraceService(DIR);
        LogAnalysisReport rep = new LogAnalysisService(svc).analyze(
                new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8)), "log.log", "9.14", null, DIR, null, null, true, "Mighty");
        return rep.apis().stream().filter(a -> "getRouteStatus".equals(a.operation())).findFirst().orElseThrow();
    }

    @Test
    void allFourNestedBackendsAreTraced() {
        RouteTraceService svc = new RouteTraceService(DIR);
        ImpactIndex idx = svc.impactIndex(new TraceRequest(null, "9.14", null, DIR, null, null, null, List.of()));
        ApiImpact api = idx.getApis().stream().filter(a -> "getRouteStatus".equals(a.operation())).findFirst().orElseThrow();
        assertThat(api.backends()).containsExactlyInAnyOrder("/myinfo", "/fetchsub", "/getsub", "/getbe");
        assertThat(api.changeFlows()).hasSize(4);
    }

    @Test
    void oneTransactionCoveringTwoOfFourBackendsIsPartial() throws IOException {
        String log = fe("C1", "Request", "100") + be("C1", "/myinfo", "200") + be("C1", "/fetchsub", "300") + fe("C1", "Response", "500");
        ApiLogResult api = analyze(log);

        assertThat(api.status()).isEqualTo(LogStatus.PARTIAL);
        assertThat(api.note()).contains("not tested").contains("/getsub").contains("/getbe");
        assertThat(api.backends()).filteredOn(b -> b.status() == LogStatus.NOT_TESTED)
                .extracting(BackendCallResult::backend).containsExactlyInAnyOrder("/getsub", "/getbe");
    }

    @Test
    void logsCoveringEveryBranchAcrossTransactionsAreSuccess() throws IOException {
        // Three transactions together exercise all four backends → the loop is closed → SUCCESS.
        String log =
                fe("C1", "Request", "100") + be("C1", "/myinfo", "110") + be("C1", "/fetchsub", "120") + fe("C1", "Response", "150")
              + fe("C2", "Request", "200") + be("C2", "/myinfo", "210") + be("C2", "/getsub", "220") + fe("C2", "Response", "250")
              + fe("C3", "Request", "300") + be("C3", "/myinfo", "310") + be("C3", "/getbe", "320") + fe("C3", "Response", "350");
        assertThat(analyze(log).status()).isEqualTo(LogStatus.SUCCESS);
    }
}

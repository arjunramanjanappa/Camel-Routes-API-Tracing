package com.uob.tracer;

import com.uob.tracer.api.ApiLogResult;
import com.uob.tracer.api.LogAnalysisReport;
import com.uob.tracer.api.LogStatus;
import com.uob.tracer.service.LogAnalysisService;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user's new log grouping: a backend path with NO leading slash (ft/bfs/txn/confirm), a BE request line
 * with a "[jwt]: false," prefix, and a BE response line without it. The request+response must still pair so
 * the backend reads SUCCESS, not TIMEOUT. All four lines share one 32-hex correlation id.
 */
class JwtNoSlashBackendTest {

    private final RouteTraceService svc = new RouteTraceService("src/test/resources/svc-jwt-noslash");

    private static final String CORR = "abcdef0123456789abcdef0123456789";
    private static final String LOG =
        "2026-06-11 18.43.45.102 [XNIO-1 task-4] INFO [MightyMessage][MTY][sess1][user1][9.14][" + CORR + "][][]-/mty-banking/services/sg/manage/confirm -Request - {\"serviceRequest\":{}}\n"
      + "2026-06-11 18.43.45.310 [XNIO-1 task-4] INFO [MightyHostMessage][MTY][sess1][user1][9.14][" + CORR + "][][]-[jwt]: false,    ft/bfs/txn/confirm -[Request] : {\"serviceRequest\":{}}\n"
      + "2026-06-11 18.43.45.318 [XNIO-1 task-4] INFO [MightyHostMessage][MTY][sess1][9.14][" + CORR + "][][230ms]- ft/bfs/txn/confirm -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n"
      + "2026-06-11 18.43.45.502 [XNIO-1 task-4] INFO [MightyMessage][MTY][sess1][user1][9.14][" + CORR + "][Android][500ms]-/mty-banking/services/sg/manage/confirm -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";

    @Test
    void jwtPrefixOnANoSlashBackendUrlDoesNotSplitTheRequestFromItsResponse() throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(LOG.getBytes(StandardCharsets.UTF_8))) {
            LogAnalysisReport rep = new LogAnalysisService(svc).analyze(in, "log.log", "9.14", null,
                    "src/test/resources/svc-jwt-noslash", null, null, true, "Mighty");
            ApiLogResult api = rep.apis().stream().filter(a -> "confirm".equals(a.operation()))
                    .findFirst().orElseThrow(() -> new AssertionError("confirm not in report; apis=" + rep.apis()));

            // The backend request (with "[jwt]: false,") and its response now parse to the SAME path, so they
            // pair into one SUCCESS call — not a phantom TIMEOUT request + orphan response.
            assertThat(api.backends()).anySatisfy(b -> {
                assertThat(b.backend()).contains("ft/bfs/txn/confirm");
                assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
            });
            // No backend row should be a TIMEOUT (the pre-fix bug showed a /bfs/txn/confirm request as timed out).
            assertThat(api.backends()).noneSatisfy(b -> assertThat(b.status()).isEqualTo(LogStatus.TIMEOUT));
            assertThat(api.status()).isEqualTo(LogStatus.SUCCESS);
        }
    }
}

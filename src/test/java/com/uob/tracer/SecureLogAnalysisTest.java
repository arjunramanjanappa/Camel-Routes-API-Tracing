package com.uob.tracer;

import com.uob.tracer.api.ApiLogResult;
import com.uob.tracer.api.BackendCallResult;
import com.uob.tracer.api.LogAnalysisReport;
import com.uob.tracer.api.LogStatus;
import com.uob.tracer.service.LogAnalysisService;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Log correlation for the auto-detected <b>SPL-Secure</b> flavour (intercepted UFW / command
 * dispatch). Only this flavour logs its front-end lines with the SPLAppLog (request) /
 * SPLWSAppLog (response) loggers, a {@code corrId|spanId|} prefix (request) or a TRACE-ID
 * header (response), and no version field; the backend keeps the standard SPLHostMessage shape.
 *
 * <p>Detection is by the {@code direct:redirectRoute} dispatcher marker in the source, so Mighty
 * and regular SPL (no such marker) keep the standard log parser untouched — verified by feeding
 * the very same secure log as "Mighty" and getting nothing back.
 */
class SecureLogAnalysisTest {

    private static final String CORR = "4bf92f3577b34da6a3ce929d0e0e4736";   // 32-hex trace id
    private static final String SPAN = "00f067aa0ba902b7";                    // 16-hex span id

    /** A minimal SPL-Secure repo: the fixed dispatcher marker + one command route + a call route. */
    private static void writeSecureRepo(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("routes"));
        Files.writeString(dir.resolve("routes/secure-MY.xml"),
                "<routes>"
                        + "<route id=\"redirectRoute\"><from uri=\"direct:redirectRoute\"/>"
                        + "<toD uri=\"direct:send${header.operationName}Route\"/></route>"
                        + "<route id=\"sendValidateNotificationCommandRoute\">"
                        + "<from uri=\"direct:sendValidateNotificationCommandRoute\"/>"
                        + "<setProperty name=\"api\"><simple>/bfs/validate</simple></setProperty>"
                        + "<to uri=\"direct:callHost\"/></route>"
                        + "<route id=\"callHost\"><from uri=\"direct:callHost\"/>"
                        + "<setHeader name=\"CamelHttpUri\"><simple>${exchangeProperty.api}</simple></setHeader>"
                        + "<toD uri=\"${header.CamelHttpUri}\"/></route>"
                        + "</routes>");
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"),
                "camel:\n  main:\n    routes-include-pattern: classpath:routes/secure-${country:}.xml\n");
        Files.writeString(dir.resolve("PublicApiController.java"),
                "package com.x.secure;\n"
                        + "import org.springframework.web.bind.annotation.*;\n"
                        + "@RestController @RequestMapping(\"/services\")\n"
                        + "public class PublicApiController {\n"
                        + "  @CommandHandler(command=\"ValidateNotificationCommand\") @PostMapping(\"/get/push\")\n"
                        + "  public Object validateNotification(Object b){ return null; }\n"
                        + "}\n");
    }

    /** A full secure transaction: FE request/response (SPLAppLog/SPLWSAppLog) + BE request/response (SPLHostMessage). */
    private static String secureLog() {
        return String.join("\n",
                // FE request — corrId in the "corrId|spanId|" prefix, no version field.
                "2026-06-11 18.43.45.102 " + CORR + "|" + SPAN + "| [http-1] [INFO ] [SPLAppLog] - /services/get/push - Request - {\"amount\":10}",
                // BE request/response — standard SPLHostMessage, country MY + release 9.14 in the brackets.
                "2026-06-11 18.43.45.150 [http-1] INFO  [SPLHostMessage][][][MY][9.14][" + CORR + "][][] - https://host/bfs/validate - [Request]: {\"serviceVersionNumber\":\"2.0\"}",
                "2026-06-11 18.43.45.300 [http-1] INFO  [SPLHostMessage][][][MY][9.14][" + CORR + "][][] - https://host/bfs/validate - [Response]: {\"responseCode\":\"0000\"}",
                // FE response — NO path (tied to its request by corrId); empty "||" prefix, id only
                // in the TRACE-ID header, payload wrapped as status/body/headers.
                "2026-06-11 18.43.45.350 || [http-1] [INFO ] [SPLWSAppLog] - Response: status=200, body={\"responseCode\":\"0000000\"}, headers={CONTENT-TYPE=application/json, TRACE-ID=" + CORR + ", SPAN-ID=" + SPAN + "}");
    }

    private LogAnalysisReport analyze(Path repo, String app) throws Exception {
        LogAnalysisService service = new LogAnalysisService(new RouteTraceService(repo.toString()));
        try (InputStream in = new ByteArrayInputStream(secureLog().getBytes(StandardCharsets.UTF_8))) {
            // country MY, client release N/A (the secure repo is unversioned → base routes), all=true.
            return service.analyze(in, "secure.log", "N/A", "MY", repo.toString(), null, null, true, app);
        }
    }

    @Test
    void secureFrontEndAndBackendCorrelateEndToEnd(@TempDir Path dir) throws Exception {
        writeSecureRepo(dir);

        LogAnalysisReport r = analyze(dir, "SPL");

        assertThat(r.matchedLines()).isEqualTo(4);      // both FE loggers + both host lines parsed
        assertThat(r.unparsedLines()).isZero();
        assertThat(r.transactions()).isEqualTo(1);      // all four share the one 32-hex trace id

        ApiLogResult api = r.apis().stream()
                .filter(a -> a.api().equals("/services/get/push")).findFirst().orElseThrow();
        assertThat(api.tested()).isTrue();
        assertThat(api.status()).isEqualTo(LogStatus.SUCCESS);       // FE 0000000, backend 0000
        assertThat(api.correlationId()).isEqualTo(CORR);             // found in prefix (req) and TRACE-ID (resp)
        assertThat(api.responseCode()).matches("0+");
        // Version came from the SPLHostMessage line (9.14) even though the FE lines carry none.
        assertThat(api.backends()).anySatisfy((BackendCallResult b) -> {
            assertThat(b.backend()).contains("/bfs/validate");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
            assertThat(b.loggedServiceVersion()).isEqualTo("2.0");
        });
    }

    @Test
    void secureBackendResponseCode200IsSuccess(@TempDir Path dir) throws Exception {
        // In the secure repo a backend responseCode of 200 (HTTP OK) is success, the same as
        // all-zeros. Before this, 200 failed the all-zeros rule → backend FAILED → overall PARTIAL.
        writeSecureRepo(dir);
        String log = String.join("\n",
                "2026-06-11 18.43.45.102 " + CORR + "|" + SPAN + "| [http-1] [INFO ] [SPLAppLog] - /services/get/push - Request - {\"amount\":10}",
                "2026-06-11 18.43.45.150 [http-1] INFO  [SPLHostMessage][][][MY][9.14][" + CORR + "][][] - https://host/bfs/validate - [Request]: {\"serviceVersionNumber\":\"2.0\"}",
                "2026-06-11 18.43.45.300 [http-1] INFO  [SPLHostMessage][][][MY][9.14][" + CORR + "][][] - https://host/bfs/validate - [Response]: {\"responseCode\":\"200\"}",
                "2026-06-11 18.43.45.350 || [http-1] [INFO ] [SPLWSAppLog] - Response: status=200, body={\"responseCode\":\"0000000\"}, headers={TRACE-ID=" + CORR + ", SPAN-ID=" + SPAN + "}");

        LogAnalysisService service = new LogAnalysisService(new RouteTraceService(dir.toString()));
        LogAnalysisReport r;
        try (InputStream in = new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8))) {
            r = service.analyze(in, "secure.log", "N/A", "MY", dir.toString(), null, null, true, "SPL");
        }

        ApiLogResult api = r.apis().stream()
                .filter(a -> a.api().equals("/services/get/push")).findFirst().orElseThrow();
        assertThat(api.status()).isEqualTo(LogStatus.SUCCESS);   // not PARTIAL — 200 is a success code here
        assertThat(api.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/validate");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
            assertThat(b.responseCode()).isEqualTo("200");
        });
    }

    @Test
    void feResponseWithoutResponseCodeInBodyIsIndeterminate(@TempDir Path dir) throws Exception {
        // The HTTP status (200) is NOT used as a success signal — a 200 can wrap a business
        // failure. When body={…} carries no responseCode, the front-end verdict is indeterminate.
        writeSecureRepo(dir);
        String log = String.join("\n",
                "2026-06-11 18.43.45.102 " + CORR + "|" + SPAN + "| [http-1] [INFO ] [SPLAppLog] - /services/get/push - Request - {\"amount\":10}",
                "2026-06-11 18.43.45.350 || [http-1] [INFO ] [SPLWSAppLog] - /services/get/push - Response: status=200, body={\"data\":\"ok\"}, headers={TRACE-ID=" + CORR + ", SPAN-ID=" + SPAN + "}");

        LogAnalysisService service = new LogAnalysisService(new RouteTraceService(dir.toString()));
        LogAnalysisReport r;
        try (InputStream in = new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8))) {
            r = service.analyze(in, "secure.log", "N/A", "MY", dir.toString(), null, null, true, "SPL");
        }

        ApiLogResult api = r.apis().stream()
                .filter(a -> a.api().equals("/services/get/push")).findFirst().orElseThrow();
        assertThat(api.status()).isEqualTo(LogStatus.INDETERMINATE);   // status=200 not treated as success
        assertThat(api.responseCode()).isNull();
    }

    @Test
    void sameSecureLogAnalysedAsMightyFindsNothing(@TempDir Path dir) throws Exception {
        // The secure FE loggers are recognised only because the SOURCE auto-detects as secure.
        // Analysed as Mighty against a NON-secure repo, the standard parser looks for
        // MightyMessage / MightyHostMessage and ignores every SPLAppLog / SPLWSAppLog / SPLHostMessage
        // line — proving the variant can't leak into the apps we already built.
        Files.writeString(dir.resolve("Api.java"),
                "package com.x;\n"
                        + "import org.springframework.web.bind.annotation.*;\n"
                        + "@RestController @RequestMapping(\"/services\")\n"
                        + "public class Api {\n"
                        + "  @PostMapping(\"/get/push\") public Object push(Object b){ return null; }\n"
                        + "}\n");

        LogAnalysisReport r = analyze(dir, "Mighty");

        assertThat(r.matchedLines()).isZero();          // no Mighty markers present
        assertThat(r.transactions()).isZero();
        assertThat(r.apis()).allSatisfy(a -> assertThat(a.status()).isEqualTo(LogStatus.NOT_TESTED));
    }
}

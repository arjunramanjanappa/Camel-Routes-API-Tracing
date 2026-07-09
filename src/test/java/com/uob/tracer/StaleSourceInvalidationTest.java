package com.uob.tracer;

import com.uob.tracer.api.ApiDiff;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.VersionDiffReport;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A release-diff (and impact) result must reflect edits to the source templates/routes
 * on the next reload, without restarting the app. The service keeps warm caches while the
 * tree is unchanged, but fingerprints the source on each Load/Compare/Trace and rebuilds
 * when a file changed — so re-running picks up the edit.
 */
class StaleSourceInvalidationTest {

    private ApiDiff payApi(VersionDiffReport report) {
        return report.getApis().stream()
                .filter(a -> a.operation().equals("payApi"))
                .findFirst().orElseThrow();
    }

    @Test
    void editingALowerTemplateBetweenComparesIsPickedUpWithoutRestart(@TempDir Path root) throws IOException {
        writeFixture(root, /* lowerKeys */ "\"channelId\": \"MTY\",\n  \"amount\": \"${amount}\"");
        RouteTraceService service = new RouteTraceService(root.toString());

        // First compare: both versions' templates carry the same keys -> no payload change.
        ApiDiff before = payApi(service.versionDiff(new TraceRequest(null, "9.4", null, null)));
        assertThat(before.status()).isEqualTo(ApiDiff.CHANGED);   // the <to> template uri still differs
        assertThat(before.payloadChange()).isNull();

        // Remove the "amount" key from the OLDER (9.3) template. It now exists only in the
        // newer 9.4 template -> on re-compare it must surface as an added payload key.
        Path lower = root.resolve("META-INF/templates/pay/v93/req.ftl");
        Files.writeString(lower, "{\n  \"channelId\": \"MTY\"\n}\n");

        ApiDiff after = payApi(service.versionDiff(new TraceRequest(null, "9.4", null, null)));
        assertThat(after.payloadChange()).isNotNull();
        assertThat(after.payloadChange().addedKeys()).contains("amount");
        assertThat(after.payloadChange().removedKeys()).isEmpty();
    }

    /** A minimal framework: one API (payApi) with a 9.3 and a 9.4 route, each using its own template. */
    private void writeFixture(Path root, String lowerBodyKeys) throws IOException {
        write(root.resolve("controllers/PayController.java"),
                "package demo;\n"
                        + "import org.springframework.web.bind.annotation.*;\n"
                        + "@RestController @RequestMapping(\"/pay\")\n"
                        + "class PayController {\n"
                        + "  @PostMapping(\"/do\") public Object payApi(@RequestBody Object b){ return null; }\n"
                        + "}\n");

        write(root.resolve("routes/R9.3_pay.xml"),
                "<routes xmlns=\"http://camel.apache.org/schema/spring\">\n"
                        + "  <route id=\"R9.3_payApi\">\n"
                        + "    <from uri=\"direct:R9.3_payApi\"/>\n"
                        + "    <to uri=\"freemarker:META-INF/templates/pay/v93/req.ftl\"/>\n"
                        + "  </route>\n"
                        + "</routes>\n");
        write(root.resolve("routes/R9.4_pay.xml"),
                "<routes xmlns=\"http://camel.apache.org/schema/spring\">\n"
                        + "  <route id=\"R9.4_payApi\">\n"
                        + "    <from uri=\"direct:R9.4_payApi\"/>\n"
                        + "    <to uri=\"freemarker:META-INF/templates/pay/v94/req.ftl\"/>\n"
                        + "  </route>\n"
                        + "</routes>\n");

        write(root.resolve("META-INF/templates/pay/v93/req.ftl"), "{\n  " + lowerBodyKeys + "\n}\n");
        write(root.resolve("META-INF/templates/pay/v94/req.ftl"),
                "{\n  \"channelId\": \"MTY\",\n  \"amount\": \"${amount}\"\n}\n");
    }

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}

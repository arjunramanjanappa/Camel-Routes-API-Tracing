package io.traceguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The business-area label store behind the Release Impact reports: keys are normalised the way the frontend
 * matches paths, user edits round-trip through {@code feature-labels.json}, the format-hint key is ignored, and
 * the bundled seed never overwrites an existing file.
 */
class FeatureLabelsServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldReturnEmptyMapWhenNoFileExists(@TempDir Path dir) {
        FeatureLabelsService service = new FeatureLabelsService(dir.toString(), mapper);

        assertThat(service.readAll()).isEmpty();
        assertThat(Files.exists(dir.resolve("feature-labels.json"))).isFalse();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "payment, payment",
            "Payment/FundTransfer/Quick, payment/fundtransfer/quick",
            "/services/sg/payment/v1/fundtransfer/quick/, payment/fundtransfer/quick",
            "payment/v2/fund, payment/fund",
            "payment//fund, payment/fund",
            "'  ', ''",
    })
    void shouldNormaliseKeyLikeTheFrontendMatches(String raw, String expected) {
        assertThat(FeatureLabelsService.normaliseKey(raw)).isEqualTo(expected);
    }

    @Test
    void shouldRoundTripLabelsAndDropBlankEntriesWhenSaving(@TempDir Path dir) {
        FeatureLabelsService service = new FeatureLabelsService(dir.toString(), mapper);
        Map<String, String> edit = new LinkedHashMap<>();
        edit.put("Payment", "Funds Transfer & Payments");
        edit.put("payment/v1/fundtransfer/quick", "Quick Transfer");
        edit.put("   ", "ignored");
        edit.put("manage", "   ");

        Map<String, String> saved = service.saveAll(edit);

        assertThat(saved).containsExactly(
                Map.entry("payment", "Funds Transfer & Payments"),
                Map.entry("payment/fundtransfer/quick", "Quick Transfer"));
        assertThat(new FeatureLabelsService(dir.toString(), mapper).readAll()).isEqualTo(saved);
    }

    @Test
    void shouldIgnoreTheCommentKeyWhenReading(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("feature-labels.json"),
                "{\"_comment\":\"format hint\",\"payment\":\"Funds Transfer & Payments\"}");
        FeatureLabelsService service = new FeatureLabelsService(dir.toString(), mapper);

        assertThat(service.readAll()).containsExactly(Map.entry("payment", "Funds Transfer & Payments"));
    }

    @Test
    void shouldSeedWhenAbsentButNeverOverwriteExisting(@TempDir Path dir) {
        FeatureLabelsService service = new FeatureLabelsService(dir.toString(), mapper);

        assertThat(service.seedIfAbsent("{\"payment\":\"Funds Transfer & Payments\"}".getBytes())).isTrue();
        assertThat(service.seedIfAbsent("{\"manage\":\"Manage\"}".getBytes())).isFalse();

        assertThat(service.readAll()).containsOnlyKeys("payment");
    }

    @Test
    void shouldTreatUnreadableFileAsEmpty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("feature-labels.json"), "not json");
        FeatureLabelsService service = new FeatureLabelsService(dir.toString(), mapper);

        assertThat(service.readAll()).isEmpty();
    }
}

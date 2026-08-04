package com.uob.tracer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uob.tracer.service.AppConfigService;
import com.uob.tracer.service.ConfigSeeder;
import com.uob.tracer.service.LogRulesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * First-run config seeding: a bundled rules / module-mapping seed is copied into the machine-wide home
 * only when that config is absent (user edits always win), and tokens are never seeded.
 */
class ConfigSeedingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void seedsRulesWhenAbsentButNeverOverwritesExisting(@TempDir Path dir) {
        LogRulesService rules = new LogRulesService(dir.toString(), mapper);
        byte[] seed = "{\"Mighty\":{\"codeFields\":[\"resultCode\"],\"rules\":[]}}".getBytes();

        assertThat(rules.seedIfAbsent(seed)).isTrue();
        assertThat(rules.rulesFor("Mighty", false).codeFields()).contains("resultCode");

        // A second seed (e.g. a later app start) must NOT clobber the now-existing, possibly user-edited file.
        assertThat(rules.seedIfAbsent("{\"SPL\":{\"codeFields\":[\"statusCode\"],\"rules\":[]}}".getBytes()))
                .isFalse();
        assertThat(rules.readAll()).containsKey("Mighty").doesNotContainKey("SPL");
    }

    @Test
    void seedsModuleMappingsWhenAbsentButNeverOverwritesExisting(@TempDir Path dir) {
        AppConfigService modules = new AppConfigService(dir.toString(), "", mapper);
        byte[] seed = "{\"Mighty\":[{\"sourceType\":\"local\",\"sourceDir\":\"/repo\",\"repo\":null,\"branch\":null}]}".getBytes();

        assertThat(modules.seedIfAbsent(seed)).isTrue();
        assertThat(modules.readAll()).containsKey("Mighty");

        // A second seed (e.g. a later app start) must NOT clobber the now-existing, possibly user-edited file.
        assertThat(modules.seedIfAbsent("{\"SPL\":[]}".getBytes())).isFalse();
        assertThat(modules.readAll()).containsKey("Mighty").doesNotContainKey("SPL");
    }

    @Test
    void emptySeedIsNotWritten(@TempDir Path dir) {
        LogRulesService rules = new LogRulesService(dir.toString(), mapper);
        assertThat(rules.seedIfAbsent(new byte[0])).isFalse();
        assertThat(Files.exists(dir.resolve("log-rules.json"))).isFalse();
    }

    @Test
    void theBundledCommentOnlyPlaceholderSeedCreatesNoConfig(@TempDir Path dir) {
        // The committed seed resources are comment-only placeholders ({"_comment": "...format..."}), which
        // document the format but must seed nothing — no empty config files on a fresh install.
        LogRulesService rules = new LogRulesService(dir.toString(), mapper);
        AppConfigService modules = new AppConfigService(dir.toString(), "", mapper);

        new ConfigSeeder(rules, modules, mapper);   // reads the classpath config-seed/*.json placeholders

        assertThat(Files.exists(dir.resolve("log-rules.json"))).isFalse();
        assertThat(Files.exists(dir.resolve("app-modules.json"))).isFalse();
    }

    @Test
    void aCommentKeyIsIgnoredOnRead() throws Exception {
        // A "_comment" format-hint key (JSON has no comments) must be ignored, not parsed as an app entry.
        var tmp = java.nio.file.Files.createTempDirectory("traceguard-comment");
        try {
            LogRulesService rules = new LogRulesService(tmp.toString(), mapper);
            Files.writeString(tmp.resolve("log-rules.json"),
                    "{\"_comment\":\"format hint\",\"Mighty\":{\"codeFields\":[\"resultCode\"],\"rules\":[]}}");
            assertThat(rules.readAll()).containsKey("Mighty").doesNotContainKey("_comment");

            AppConfigService modules = new AppConfigService("", tmp.resolve("app-modules.json").toString(), mapper);
            Files.writeString(tmp.resolve("app-modules.json"),
                    "{\"_comment\":\"format hint\",\"Mighty\":[]}");
            assertThat(modules.readAll()).containsKey("Mighty").doesNotContainKey("_comment");
        } finally {
            try (var s = Files.walk(tmp)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }
}

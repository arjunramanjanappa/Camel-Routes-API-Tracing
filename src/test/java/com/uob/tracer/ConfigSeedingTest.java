package com.uob.tracer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uob.tracer.service.AppConfigService;
import com.uob.tracer.service.ConfigSeeder;
import com.uob.tracer.service.LogRulesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void moduleSeedNeverOverwritesExistingConfig(@TempDir Path dir) {
        // Point at an explicit temp file so the assertion doesn't depend on the machine's ambient config.
        AppConfigService modules = new AppConfigService("", dir.resolve("app-modules.json").toString(), mapper);
        modules.save("Mighty", List.of(new AppConfigService.ModuleEntry("source", "/repo", null, null)));

        // A seed must be a no-op once config exists — the user's saved mappings win. Assert on a unique
        // key so the check is independent of any ambient config on the build machine.
        assertThat(modules.seedIfAbsent("{\"ZzzSeedApp\":[]}".getBytes())).isFalse();
        assertThat(modules.readAll()).containsKey("Mighty").doesNotContainKey("ZzzSeedApp");
    }

    @Test
    void emptySeedIsNotWritten(@TempDir Path dir) {
        LogRulesService rules = new LogRulesService(dir.toString(), mapper);
        assertThat(rules.seedIfAbsent(new byte[0])).isFalse();
        assertThat(Files.exists(dir.resolve("log-rules.json"))).isFalse();
    }

    @Test
    void theBundledPlaceholderSeedCreatesNoConfig(@TempDir Path dir) {
        // The committed seed resources are empty placeholders ({}), so a default build must seed nothing —
        // it must not create empty config files on a fresh install.
        LogRulesService rules = new LogRulesService(dir.toString(), mapper);
        AppConfigService modules = new AppConfigService(dir.toString(), "", mapper);

        new ConfigSeeder(rules, modules, mapper);   // reads the classpath config-seed/*.json placeholders

        assertThat(Files.exists(dir.resolve("log-rules.json"))).isFalse();
        assertThat(Files.exists(dir.resolve("app-modules.json"))).isFalse();
    }
}

package com.uob.tracer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.function.Predicate;

/**
 * First-run configuration seeding for the standalone / .exe distribution. The build can bundle a config
 * seed — {@code config-seed/log-rules.json} (host response-code rules) and {@code config-seed/app-modules.json}
 * (per-app module mappings) — so a fresh install starts with the team's one-time setup instead of an empty
 * tool. On startup each seed is copied into the machine-wide home ({@code ~/.traceguard}) ONLY when that
 * config file does not exist yet, so later edits (via the UI) are never overwritten.
 *
 * <p><b>Access tokens are never seeded.</b> Only the rules and module-mapping files are bundled; the
 * Bitbucket / npm tokens live in {@code settings.json}, which has no seed — each user supplies their own.
 * The two bundled files carry no secrets by construction (rules hold code fields / success codes; module
 * mappings hold source type / dir / repo / branch), so the shipped seed is safe to distribute.
 *
 * <p>To populate the seed for a build: copy your configured {@code ~/.traceguard/log-rules.json} and
 * {@code ~/.traceguard/app-modules.json} over the placeholder files in {@code src/main/resources/config-seed/}
 * before packaging. An empty placeholder ({@code {}}) seeds nothing.
 */
@Component
public class ConfigSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigSeeder.class);

    public ConfigSeeder(LogRulesService logRules, AppConfigService appConfig, ObjectMapper mapper) {
        seed("config-seed/log-rules.json", mapper, logRules::seedIfAbsent);
        seed("config-seed/app-modules.json", mapper, appConfig::seedIfAbsent);
    }

    /** Read a bundled seed resource and, if it holds meaningful JSON, hand it to the target's seedIfAbsent. */
    private void seed(String resource, ObjectMapper mapper, Predicate<byte[]> seedIfAbsent) {
        ClassPathResource res = new ClassPathResource(resource);
        if (!res.exists()) {
            return;   // no seed bundled in this build — nothing to do
        }
        byte[] bytes;
        try (InputStream in = res.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (Exception e) {
            LOG.warn("Could not read the bundled seed {} ({})", resource, e.getMessage());
            return;
        }
        // Skip an empty / placeholder seed ({} or blank) — no point creating an empty config file. Also
        // validate it parses, so a malformed seed is never written over the (absent) real config.
        String text = new String(bytes).trim();
        if (text.isEmpty() || text.equals("{}")) {
            return;
        }
        try {
            mapper.readTree(bytes);
        } catch (Exception e) {
            LOG.warn("Ignoring malformed bundled seed {} ({})", resource, e.getMessage());
            return;
        }
        seedIfAbsent.test(bytes);
    }
}

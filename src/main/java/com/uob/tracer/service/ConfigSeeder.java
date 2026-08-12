package com.uob.tracer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Configuration seeding for the standalone / .exe distribution. The build bundles a config seed —
 * {@code config-seed/log-rules.json} (host response-code rules) and {@code config-seed/app-modules.json}
 * (per-app module mappings) — so a fresh install starts with the team's one-time setup, and a later package
 * can push corrected/updated defaults to existing users WITHOUT losing their edits.
 *
 * <p>On startup, per config file:
 * <ul>
 *   <li><b>Fresh install</b> (no config yet) → copy the bundled seed in.</li>
 *   <li><b>Existing install, seed unchanged since last applied</b> → do nothing.</li>
 *   <li><b>Existing install, seed changed</b> (a new package shipped updated defaults) → 3-way merge the new
 *       seed into the user's config (see {@link SeedMerge}): corrected defaults reach keys the user hasn't
 *       touched, brand-new defaults are added, and every user edit / deletion is preserved.</li>
 * </ul>
 * The seed the package applied is recorded next to the config as {@code .seed.<name>} so the next upgrade can
 * tell what changed. This lets a package ship an updated default while a tester's own rule add/deletes survive.
 *
 * <p><b>Access tokens are never seeded or merged.</b> Only the rules and module-mapping files are bundled;
 * the Bitbucket / npm tokens live in {@code settings.json}, which has no seed — each user supplies their own.
 *
 * <p>To ship a team setup: copy your configured {@code ~/.traceguard/log-rules.json} and
 * {@code ~/.traceguard/app-modules.json} over the placeholders in {@code src/main/resources/config-seed/}
 * before packaging. An empty placeholder ({@code {}}) seeds nothing and is never treated as "defaults removed".
 */
@Component
public class ConfigSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigSeeder.class);

    private final ObjectMapper mapper;

    public ConfigSeeder(LogRulesService logRules, AppConfigService appConfig, CapabilityService capabilities, ObjectMapper mapper) {
        this.mapper = mapper;
        apply("config-seed/log-rules.json", logRules.file(), logRules::seedIfAbsent, logRules::overwrite);
        apply("config-seed/app-modules.json", appConfig.file(), appConfig::seedIfAbsent, appConfig::overwrite);
        // The VAL reports are binary .xlsx — seed-if-absent only (no 3-way merge). Drop the team's files at
        // config-seed/val-interface-spec.xlsx / config-seed/val-capability-matrix.xlsx before packaging the exe;
        // a fresh install gets them, and the user can later replace via ⚙ Config → Replace.
        seedBinary("config-seed/val-interface-spec.xlsx", capabilities::seedInterfaceSpecIfAbsent);
        seedBinary("config-seed/val-capability-matrix.xlsx", capabilities::seedCapabilityMatrixIfAbsent);
    }

    /** Seed a bundled BINARY resource (e.g. a VAL .xlsx) only when the target file doesn't exist yet. */
    private void seedBinary(String resource, Predicate<byte[]> seedIfAbsent) {
        ClassPathResource res = new ClassPathResource(resource);
        if (!res.exists()) {
            return;   // no bundled file — nothing to seed
        }
        try (InputStream in = res.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length > 0) {
                seedIfAbsent.test(bytes);
            }
        } catch (Exception e) {
            LOG.warn("Could not seed the bundled resource {} ({})", resource, e.getMessage());
        }
    }

    /**
     * Seed (fresh install) or 3-way-merge (updated package) the bundled seed for one config file.
     *
     * @param resource     the bundled seed's classpath location
     * @param file         the resolved config file (its {@code .seed.<name>} sibling records the applied seed)
     * @param seedIfAbsent writes the seed when the config file doesn't exist yet; returns whether it wrote
     * @param overwrite    atomically replaces the config file with pre-merged JSON
     */
    private void apply(String resource, Path file, Predicate<byte[]> seedIfAbsent, Consumer<byte[]> overwrite) {
        byte[] theirs = readMeaningfulSeed(resource);
        if (theirs == null) {
            return;   // no bundled seed, or an empty {} / malformed placeholder — nothing to apply
        }
        Path baseline = file.resolveSibling(".seed." + file.getFileName());
        try {
            if (!Files.exists(file)) {
                if (seedIfAbsent.test(theirs)) {   // fresh install — seedIfAbsent respects the legacy fallback too
                    writeBaseline(baseline, theirs);
                    LOG.info("Seeded {} from the bundled config", file.getFileName());
                }
                return;
            }
            byte[] base = Files.exists(baseline) ? Files.readAllBytes(baseline) : null;
            if (base != null && SeedMerge.jsonEquals(theirs, base, mapper)) {
                return;   // the package's seed hasn't changed since we last applied it — nothing to push
            }
            byte[] ours = Files.readAllBytes(file);
            byte[] merged = SeedMerge.merge(base, ours, theirs, mapper);
            if (!SeedMerge.jsonEquals(merged, ours, mapper)) {
                overwrite.accept(merged);
                LOG.info("Merged updated bundled defaults into {} (user edits preserved)", file.getFileName());
            }
            writeBaseline(baseline, theirs);   // record what we've now applied, so the next upgrade diffs from it
        } catch (Exception e) {
            LOG.warn("Config seed/merge for {} failed ({}) — leaving the user's config untouched", file, e.getMessage());
        }
    }

    /**
     * The bundled seed's bytes with any {@code "_comment"} documentation key removed, or null when it is
     * absent, not JSON, or carries no real content (blank / {@code {}} / comment-only placeholder). A
     * comment-only seed is treated exactly like {@code {}} — it documents the format but seeds nothing.
     */
    private byte[] readMeaningfulSeed(String resource) {
        ClassPathResource res = new ClassPathResource(resource);
        if (!res.exists()) {
            return null;
        }
        byte[] bytes;
        try (InputStream in = res.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (Exception e) {
            LOG.warn("Could not read the bundled seed {} ({})", resource, e.getMessage());
            return null;
        }
        try {
            var tree = mapper.readTree(bytes);
            SeedMerge.stripCommentKeys(tree);         // drop the "_comment" format hint before acting on it
            if (!tree.isObject() || tree.isEmpty()) {
                return null;   // placeholder / comment-only — documents the format but seeds nothing
            }
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(tree);
        } catch (Exception e) {
            LOG.warn("Ignoring malformed bundled seed {} ({})", resource, e.getMessage());
            return null;
        }
    }

    private void writeBaseline(Path baseline, byte[] seed) {
        try {
            Path parent = baseline.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(baseline, seed);
        } catch (Exception e) {
            LOG.warn("Could not record the applied seed baseline {} ({})", baseline, e.getMessage());
        }
    }
}

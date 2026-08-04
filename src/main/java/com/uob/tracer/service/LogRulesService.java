package com.uob.tracer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Machine-wide, per-app <b>host response-code rules</b> for log analysis — persisted to
 * {@code ~/.traceguard/log-rules.json} (the same home as {@code settings.json} / {@code app-modules.json}) so
 * it is controlled once and shared by the standalone bundle and IntelliJ.
 *
 * <p>Some host/backend APIs report their outcome in a non-standard shape — e.g. {@code "resultCode":"000000"}
 * instead of {@code "responseCode"}, or a success value that isn't all-zeros/200 — and some shouldn't count
 * toward a release verdict at all. These rules, keyed by marker/app ({@code Mighty} / {@code SPL} /
 * {@code SPL-Secure}), tell the analyzer, for a matching <b>backend hosturl</b>:
 * <ul>
 *   <li>which JSON key to read the code from ({@code codeField}, else the app-wide {@code codeFields} fallback),</li>
 *   <li>what counts as success ({@code successCodes}, else the built-in all-zeros/200 rule), and</li>
 *   <li>whether to {@code skip} the backend entirely (→ {@code SKIPPED}, neither pass nor fail).</li>
 * </ul>
 * Front-end (controller) lines are unaffected — these rules are host/backend only.
 *
 * <p>Shape: {@code { "Mighty": { "codeFields": [...], "rules": [ {match, codeField, successCodes, skip}, ... ] } } }.
 */
@Service
public class LogRulesService {

    private static final Logger LOG = LoggerFactory.getLogger(LogRulesService.class);

    /** One host-backend rule. {@code match} is a glob on the backend hosturl (e.g. {@code *&#47;host&#47;limit&#47;*}). */
    public record Rule(String match, String codeField, List<String> successCodes, boolean skip) {
        public Rule {
            match = match == null ? "" : match.trim();
            codeField = codeField == null ? "" : codeField.trim();
            successCodes = successCodes == null ? List.of() : List.copyOf(successCodes);
        }
    }

    /** The rules for one app/marker: an ordered {@code codeFields} fallback list plus per-hosturl {@code rules}. */
    public record AppRules(List<String> codeFields, List<Rule> rules) {
        public AppRules {
            codeFields = codeFields == null ? List.of() : List.copyOf(codeFields);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }

        public static AppRules empty() {
            return new AppRules(List.of(), List.of());
        }

        /**
         * The ordered code keys to try when reading a backend response code — {@code responseCode} first, then
         * the app-wide {@code codeFields} fallbacks, then every rule's own {@code codeField}. Rule fields are
         * included so a per-rule {@code codeField} (e.g. {@code resultCode}) is actually READ at parse time; the
         * matching rule then judges success against its {@code successCodes}.
         */
        public List<String> effectiveCodeFields() {
            List<String> out = new ArrayList<>();
            out.add("responseCode");
            for (String f : codeFields) {
                if (f != null && !f.isBlank() && !out.contains(f.trim())) {
                    out.add(f.trim());
                }
            }
            for (Rule r : rules) {
                String f = r.codeField();
                if (f != null && !f.isBlank() && !out.contains(f.trim())) {
                    out.add(f.trim());
                }
            }
            return out;
        }

        /** The first rule whose {@code match} glob matches this hosturl/path, or null. Rule order is honoured. */
        public Rule ruleFor(String path) {
            if (path == null || path.isBlank()) {
                return null;
            }
            for (Rule r : rules) {
                if (!r.match().isBlank() && globMatches(r.match(), path)) {
                    return r;
                }
            }
            return null;
        }
    }

    private final Path home;
    private final Path file;
    private final ObjectMapper mapper;
    private final Object lock = new Object();

    public LogRulesService(@Value("${tracer.home:}") String home, ObjectMapper mapper) {
        this.home = resolveHome(home);
        this.file = this.home.resolve("log-rules.json");
        this.mapper = mapper;
    }

    /** The app/marker key for a set of rules: {@code SPL-Secure} for the secure flavour, else the app name. */
    public static String appKey(String app, boolean secure) {
        String a = (app == null || app.isBlank()) ? "Mighty" : app.trim();
        return secure ? a + "-Secure" : a;
    }

    /** The rules for one app/marker (empty when none configured). */
    public AppRules rulesFor(String app, boolean secure) {
        return readAll().getOrDefault(appKey(app, secure), AppRules.empty());
    }

    /** Every app's rules (empty map when nothing saved). */
    public Map<String, AppRules> readAll() {
        synchronized (lock) {
            if (!Files.exists(file)) {
                return new LinkedHashMap<>();
            }
            try {
                com.fasterxml.jackson.databind.JsonNode tree = mapper.readTree(Files.readAllBytes(file));
                SeedMerge.stripCommentKeys(tree);   // ignore any "_comment" format-hint key
                return mapper.convertValue(tree,
                        mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, AppRules.class));
            } catch (Exception e) {
                LOG.warn("Could not read log rules at {} ({}); treating as empty", file, e.getMessage());
                return new LinkedHashMap<>();
            }
        }
    }

    /** Merge-save one app's rules (other apps are left untouched); returns the full map. */
    public Map<String, AppRules> saveApp(String app, boolean secure, AppRules rules) {
        synchronized (lock) {
            Map<String, AppRules> all = readAll();
            all.put(appKey(app, secure), rules == null ? AppRules.empty() : rules);
            writeAll(all);
            return all;
        }
    }

    /** Replace the whole map (for a bulk editor). */
    public Map<String, AppRules> saveAll(Map<String, AppRules> all) {
        synchronized (lock) {
            Map<String, AppRules> next = all == null ? new LinkedHashMap<>() : new LinkedHashMap<>(all);
            writeAll(next);
            return next;
        }
    }

    /**
     * First-run seed: if no rules file exists yet, write the given JSON verbatim — a rules bundle shipped
     * with the app so a fresh install starts with the team's rules (a one-time setup). NEVER overwrites an
     * existing file: user edits always win. Contains no tokens by construction (this file only holds rules).
     * Returns true if it wrote.
     */
    public boolean seedIfAbsent(byte[] json) {
        if (json == null || json.length == 0) {
            return false;
        }
        synchronized (lock) {
            if (Files.exists(file)) {
                return false;
            }
            try {
                Files.createDirectories(home);
                Path tmp = file.resolveSibling("log-rules.json.tmp");
                Files.write(tmp, json);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Seeded log rules from the bundled config into {}", file);
                return true;
            } catch (IOException e) {
                LOG.warn("Could not seed log rules to {} ({})", file, e.getMessage());
                return false;
            }
        }
    }

    /** The resolved config file — used by {@link ConfigSeeder} to locate the file and its seed baseline. */
    public Path file() {
        return file;
    }

    /** Atomically replace the whole rules file with pre-merged JSON (used by the seed 3-way merge). */
    public void overwrite(byte[] json) {
        synchronized (lock) {
            try {
                Files.createDirectories(home);
                Path tmp = file.resolveSibling("log-rules.json.tmp");
                Files.write(tmp, json);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.warn("Could not write merged log rules to {} ({})", file, e.getMessage());
            }
        }
    }

    private void writeAll(Map<String, AppRules> all) {
        try {
            Files.createDirectories(home);
            Path tmp = file.resolveSibling("log-rules.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not save log rules to " + file + ": " + e.getMessage());
        }
    }

    private static Path resolveHome(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return Path.of(System.getProperty("user.home", "."), ".traceguard");
    }

    /** Case-insensitive glob match ({@code *} = any run, {@code ?} = one char), whole-string. */
    public static boolean globMatches(String glob, String path) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                re.append(".*");
            } else if (c == '?') {
                re.append('.');
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE)
                .matcher(path.toLowerCase(Locale.ROOT).trim()).matches();
    }
}

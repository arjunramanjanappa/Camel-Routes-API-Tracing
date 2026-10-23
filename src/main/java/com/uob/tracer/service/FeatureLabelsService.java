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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Machine-wide <b>business-area labels</b> for the Release Impact reports — persisted to
 * {@code ~/.traceguard/feature-labels.json} (the same home as {@code log-rules.json}) so the names a team uses
 * in its own documents ("Funds Transfer &amp; Payments", "Quick Transfer") appear in the Summary / Detailed reports
 * instead of raw path segments ({@code payment}, {@code fundtransfer/quick}).
 *
 * <p>Shape: a flat map {@code path-key → label}. The key is the API path after {@code /services/<country>/}
 * with version segments ({@code v1}, {@code v2}) removed, lower-case: a one-segment key names an <b>area</b>
 * ({@code payment}); a longer key names a <b>sub-feature</b> ({@code payment/fundtransfer/quick}). The frontend
 * resolves a path by its longest matching prefix and falls back to the path segment itself when nothing matches,
 * so a missing label never hides an API — it just reads less nicely. Keys are normalised on save.
 */
@Service
public class FeatureLabelsService {

    private static final Logger LOG = LoggerFactory.getLogger(FeatureLabelsService.class);

    private final Path home;
    private final Path file;
    private final ObjectMapper mapper;
    private final Object lock = new Object();

    public FeatureLabelsService(@Value("${tracer.home:}") String home, ObjectMapper mapper) {
        this.home = resolveHome(home);
        this.file = this.home.resolve("feature-labels.json");
        this.mapper = mapper;
    }

    /** Every configured label, keyed by normalised path key (empty map when nothing saved). */
    public Map<String, String> readAll() {
        synchronized (lock) {
            if (!Files.exists(file)) {
                return new LinkedHashMap<>();
            }
            try {
                com.fasterxml.jackson.databind.JsonNode tree = mapper.readTree(Files.readAllBytes(file));
                SeedMerge.stripCommentKeys(tree);   // ignore the "_comment" format-hint key
                Map<String, String> raw = mapper.convertValue(tree,
                        mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
                return normalise(raw);
            } catch (Exception e) {
                LOG.warn("Could not read feature labels at {} ({}); treating as empty", file, e.getMessage());
                return new LinkedHashMap<>();
            }
        }
    }

    /** Replace the whole map (the Config editor saves everything at once); blank keys/labels are dropped. */
    public Map<String, String> saveAll(Map<String, String> all) {
        synchronized (lock) {
            Map<String, String> next = normalise(all);
            writeAll(next);
            return next;
        }
    }

    /**
     * Normalise keys the way the frontend matches them: trimmed, lower-case, {@code /}-separated, no leading or
     * trailing slash, {@code services/<country>/} prefix and {@code vN} version segments removed. Blank keys or
     * labels are dropped. Insertion order is kept so the editor shows the user's own ordering.
     */
    static Map<String, String> normalise(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in == null) {
            return out;
        }
        for (Map.Entry<String, String> e : in.entrySet()) {
            String key = normaliseKey(e.getKey());
            String label = e.getValue() == null ? "" : e.getValue().trim();
            if (!key.isEmpty() && !label.isEmpty()) {
                out.put(key, label);
            }
        }
        return out;
    }

    static String normaliseKey(String raw) {
        if (raw == null) {
            return "";
        }
        // Trim surrounding slashes first so a pasted "/services/sg/..." still starts at "services".
        String[] parts = raw.trim().toLowerCase(Locale.ROOT).replace('\\', '/')
                .replaceAll("^/+|/+$", "").split("/");
        StringBuilder sb = new StringBuilder();
        int i = 0;
        // Drop a leading "services/<country>" if someone pasted a full path.
        if (parts.length >= 2 && "services".equals(parts[0].trim())) {
            i = 2;
        }
        for (; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty() || p.matches("v\\d+")) {
                continue;   // empty from doubled slashes, or a version segment (v1, v2)
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /** First-run seed: write the bundled labels when no file exists yet; never overwrites user edits. */
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
                Path tmp = file.resolveSibling("feature-labels.json.tmp");
                Files.write(tmp, json);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Seeded feature labels from the bundled config into {}", file);
                return true;
            } catch (IOException e) {
                LOG.warn("Could not seed feature labels to {} ({})", file, e.getMessage());
                return false;
            }
        }
    }

    /** The resolved config file — used by {@link ConfigSeeder} to locate the file and its seed baseline. */
    public Path file() {
        return file;
    }

    /** Atomically replace the whole file with pre-merged JSON (used by the seed 3-way merge). */
    public void overwrite(byte[] json) {
        synchronized (lock) {
            try {
                Files.createDirectories(home);
                Path tmp = file.resolveSibling("feature-labels.json.tmp");
                Files.write(tmp, json);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.warn("Could not write merged feature labels to {} ({})", file, e.getMessage());
            }
        }
    }

    private void writeAll(Map<String, String> all) {
        try {
            Files.createDirectories(home);
            Path tmp = file.resolveSibling("feature-labels.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not save feature labels to " + file + ": " + e.getMessage());
        }
    }

    private static Path resolveHome(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return Path.of(System.getProperty("user.home", "."), ".traceguard");
    }
}

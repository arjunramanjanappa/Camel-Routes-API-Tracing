package io.traceguard.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Machine-wide <b>ignore list for Release Impact code-change detection</b> — persisted to
 * {@code ~/.traceguard/ignored-classes.json} as {@code {"patterns": [...]}}.
 *
 * <p>A common class shared by many routes (a logger wrapper, a generic audit processor, a base utility) marks
 * every API that runs it as impacted whenever it changes, which drowns the real impact. A pattern listed here
 * makes such a class invisible to the shared-class check: its change never flags an API nor promotes one to
 * Changed. It is not hidden silently — the report lists what was skipped ({@code ignoredClassChanges}).
 *
 * <p>A pattern is a case-insensitive glob ({@code *} = any run, {@code ?} = one char) matched against the bean
 * name ({@code commonAuditProcessor}), the simple class name ({@code CommonAuditProcessor}) and the repo-relative
 * file path ({@code **&#47;common&#47;*.java}, matched as a path suffix so a scan-root prefix does not matter).
 */
@Service
public class IgnoredClassesService {

    private static final Logger LOG = LoggerFactory.getLogger(IgnoredClassesService.class);
    static final String KEY = "patterns";

    private final Path home;
    private final Path file;
    private final ObjectMapper mapper;
    private final Object lock = new Object();

    public IgnoredClassesService(@Value("${tracer.home:}") String home, ObjectMapper mapper) {
        this.home = resolveHome(home);
        this.file = this.home.resolve("ignored-classes.json");
        this.mapper = mapper;
    }

    /** The configured patterns, normalised (empty when nothing saved). */
    public List<String> readAll() {
        synchronized (lock) {
            if (!Files.exists(file)) {
                return List.of();
            }
            try {
                JsonNode tree = mapper.readTree(Files.readAllBytes(file));
                SeedMerge.stripCommentKeys(tree);
                JsonNode arr = tree.get(KEY);
                List<String> raw = new ArrayList<>();
                if (arr != null && arr.isArray()) {
                    arr.forEach(n -> raw.add(n.asText()));
                }
                return normalise(raw);
            } catch (Exception e) {
                LOG.warn("Could not read ignored classes at {} ({}); treating as empty", file, e.getMessage());
                return List.of();
            }
        }
    }

    /** Replace the list (the Config editor saves everything at once); blank entries are dropped. */
    public List<String> saveAll(List<String> patterns) {
        synchronized (lock) {
            List<String> next = normalise(patterns);
            writeAll(next);
            return next;
        }
    }

    /** Trimmed, de-duplicated, blanks and {@code #} comment lines dropped; order kept. */
    static List<String> normalise(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        for (String p : in) {
            String t = p == null ? "" : p.trim();
            if (!t.isEmpty() && !t.startsWith("#") && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * True when any pattern matches the bean name, the class's simple name, or its file path. Returns the
     * matching pattern (for the report) or null.
     */
    public static String matching(List<String> patterns, String beanName, String classFile) {
        if (patterns == null || patterns.isEmpty()) {
            return null;
        }
        String path = classFile == null ? "" : classFile.replace('\\', '/');
        String simple = path.substring(path.lastIndexOf('/') + 1).replaceFirst("(?i)\\.java$", "");
        for (String p : patterns) {
            if (matchesName(p, beanName) || matchesName(p, simple) || matchesPath(p, path)) {
                return p;
            }
        }
        return null;
    }

    /** Whole-name glob (case-insensitive). */
    static boolean matchesName(String glob, String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return toRegex(glob, false).matcher(name).matches();
    }

    /** Path glob matched as a suffix of the forward-slashed path ({@code **} spans directories). */
    static boolean matchesPath(String glob, String path) {
        if (path == null || path.isEmpty() || glob.indexOf('/') < 0 && glob.indexOf("**") < 0) {
            return false;   // a plain name pattern is not a path pattern
        }
        return toRegex(glob, true).matcher(path).matches();
    }

    private static Pattern toRegex(String glob, boolean path) {
        StringBuilder re = new StringBuilder(path ? "(?:.*/)?" : "");
        String g = glob.replace('\\', '/').replaceFirst("^\\*\\*/", "");
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                } else {
                    re.append(path ? "[^/]*" : ".*");
                }
            } else if (c == '?') {
                re.append('.');
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    /** First-run seed: write the bundled list when no file exists yet; never overwrites user edits. */
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
                Path tmp = file.resolveSibling("ignored-classes.json.tmp");
                Files.write(tmp, json);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException e) {
                LOG.warn("Could not seed ignored classes to {} ({})", file, e.getMessage());
                return false;
            }
        }
    }

    public Path file() {
        return file;
    }

    /** Atomically replace the whole file with pre-merged JSON (used by the seed 3-way merge). */
    public void overwrite(byte[] json) {
        synchronized (lock) {
            try {
                Files.createDirectories(home);
                Path tmp = file.resolveSibling("ignored-classes.json.tmp");
                Files.write(tmp, json);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.warn("Could not write merged ignored classes to {} ({})", file, e.getMessage());
            }
        }
    }

    private void writeAll(List<String> patterns) {
        try {
            Files.createDirectories(home);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put(KEY, patterns);
            Path tmp = file.resolveSibling("ignored-classes.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), doc);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not save ignored classes to " + file + ": " + e.getMessage());
        }
    }

    private static Path resolveHome(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return Path.of(System.getProperty("user.home", "."), ".traceguard");
    }
}

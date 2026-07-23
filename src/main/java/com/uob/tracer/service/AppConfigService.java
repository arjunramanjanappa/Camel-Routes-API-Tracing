package com.uob.tracer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the per-app module list (main repo + its sub-modules) to a small JSON file so the three
 * tabs prepopulate instead of being retyped each session. Read by the UI on load; written back when
 * a user clicks "Save as default". The file rarely changes and can also be hand-edited by ops.
 *
 * <p>Shape: {@code { "Mighty": [ {sourceType,sourceDir,repo,branch}, ... ], "SPL": [ ... ] } }.
 */
@Service
public class AppConfigService {

    /** One configured module — the same shape the UI's ModuleSource uses, minus its client-side id. */
    public record ModuleEntry(String sourceType, String sourceDir, String repo, String branch) {}

    /** Where the module config lived before it moved under the machine-wide home; read once for migration. */
    private static final Path LEGACY_FILE = Path.of("config", "app-modules.json");

    private final Path file;
    private final ObjectMapper mapper;
    private final Object lock = new Object();   // serialise read-modify-write of the shared file

    public AppConfigService(@Value("${tracer.home:}") String home,
                            @Value("${tracer.app-config-file:}") String path,
                            ObjectMapper mapper) {
        if (path != null && !path.isBlank()) {
            this.file = Path.of(path.trim());   // explicit override (e.g. an IntelliJ run config)
        } else {
            Path base = (home != null && !home.isBlank())
                    ? Path.of(home.trim())
                    : Path.of(System.getProperty("user.home", "."), ".traceguard");
            this.file = base.resolve("app-modules.json");   // machine-wide, shared with standalone + IntelliJ
        }
        this.mapper = mapper;
    }

    /** Every app's configured module list, keyed by app name. Empty map when the file doesn't exist yet. */
    public Map<String, List<ModuleEntry>> readAll() {
        synchronized (lock) {
            Path src = Files.exists(file) ? file
                    : (Files.exists(LEGACY_FILE) ? LEGACY_FILE : null);   // fall back to the pre-move location
            if (src == null) {
                return new LinkedHashMap<>();
            }
            try {
                return mapper.readValue(Files.readAllBytes(src),
                        new TypeReference<LinkedHashMap<String, List<ModuleEntry>>>() {});
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not read the app config at " + src + ": " + e.getMessage());
            }
        }
    }

    /** Replace one app's module list and write the file (create-and-rename so a reader never sees a half file). */
    public void save(String app, List<ModuleEntry> modules) {
        if (app == null || app.isBlank()) {
            throw new IllegalArgumentException("App is required.");
        }
        synchronized (lock) {
            Map<String, List<ModuleEntry>> all = readAll();
            all.put(app.trim(), modules == null ? List.of() : modules);
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not save the app config to " + file + ": " + e.getMessage());
            }
        }
    }
}

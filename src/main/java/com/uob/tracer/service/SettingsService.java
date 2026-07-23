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
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Machine-wide settings store for the standalone (desktop) app: the user's Bitbucket and npm access
 * tokens, persisted once per machine and read on every run. It lives under a single home directory —
 * {@code ${tracer.home}} (default {@code ~/.traceguard}) — which is shared by both the standalone
 * launcher and the IntelliJ ({@code spring-boot:run}) mode, so modules and tokens saved in one are
 * seen by the other. The Bitbucket token is read live from here (see {@link SourceResolver}), so the
 * config menu takes effect without a restart.
 *
 * <p>The file is {@code settings.json}: {@code { "bitbucketToken": "…", "npmToken": "…" }}. It is
 * written with owner-only permissions where the filesystem supports it (POSIX); on Windows the file
 * inherits the user-profile ACL, which is already user-scoped. Tokens are stored in plaintext — the
 * same trust model as {@code ~/.npmrc} / a git credential store — so keep the home directory private.
 */
@Service
public class SettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);

    /** The persisted settings. Fields are never null once read (empty string = not set). */
    public record Settings(String bitbucketToken, String npmToken) {
        public Settings {
            bitbucketToken = bitbucketToken == null ? "" : bitbucketToken.trim();
            npmToken = npmToken == null ? "" : npmToken.trim();
        }
        static Settings empty() { return new Settings("", ""); }
    }

    private final Path home;
    private final Path file;
    private final ObjectMapper mapper;
    private final Object lock = new Object();

    public SettingsService(@Value("${tracer.home:}") String home, ObjectMapper mapper) {
        this.home = resolveHome(home);
        this.file = this.home.resolve("settings.json");
        this.mapper = mapper;
    }

    /** The machine-wide home directory ({@code ~/.traceguard} by default). Shared with {@link AppConfigService}. */
    public Path home() {
        return home;
    }

    /** The current settings, or empty values when nothing has been saved yet. */
    public Settings read() {
        synchronized (lock) {
            if (!Files.exists(file)) {
                return Settings.empty();
            }
            try {
                Map<String, Object> raw = mapper.readValue(Files.readAllBytes(file), Map.class);
                return new Settings(str(raw.get("bitbucketToken")), str(raw.get("npmToken")));
            } catch (IOException e) {
                LOG.warn("Could not read settings at {} ({}); treating as empty", file, e.getMessage());
                return Settings.empty();
            }
        }
    }

    /**
     * Merge-save: a {@code null} field is left unchanged (so saving one token never wipes the other),
     * while a non-null value — including {@code ""} — is written (empty string clears that token).
     */
    public Settings save(String bitbucketToken, String npmToken) {
        synchronized (lock) {
            Settings cur = read();
            Settings next = new Settings(
                    bitbucketToken == null ? cur.bitbucketToken() : bitbucketToken,
                    npmToken == null ? cur.npmToken() : npmToken);
            try {
                Files.createDirectories(home);
                Path tmp = file.resolveSibling("settings.json.tmp");
                mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), Map.of(
                        "bitbucketToken", next.bitbucketToken(),
                        "npmToken", next.npmToken()));
                lockDownPerms(tmp);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                lockDownPerms(file);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not save settings to " + file + ": " + e.getMessage());
            }
            return next;
        }
    }

    /** Live Bitbucket token (empty when not configured). */
    public String bitbucketToken() {
        return read().bitbucketToken();
    }

    private static Path resolveHome(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return Path.of(System.getProperty("user.home", "."), ".traceguard");
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    /** Best-effort: restrict the secrets file to the owner on POSIX filesystems; a no-op on Windows. */
    private static void lockDownPerms(Path p) {
        try {
            Set<PosixFilePermission> owner = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(p, owner);
        } catch (UnsupportedOperationException | IOException ignore) {
            // Windows / non-POSIX: the file sits under the user profile, already user-scoped by ACL.
        }
    }
}

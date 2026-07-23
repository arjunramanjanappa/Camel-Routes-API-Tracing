package com.uob.tracer.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.TransportHttp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Resolves a "Bitbucket branch" source to a local directory the scanner can read: clones the
 * HTTPS repo at the given branch/tag via JGit (no {@code git} binary needed) into a per-repo,
 * per-branch cache dir, and re-fetches (throttled) to pick up new commits. Authenticated with an
 * internal Bitbucket Data Center HTTP access token (Bearer), configured via {@code bitbucket.token}.
 *
 * <p>Local-path mode never touches this class; only requests carrying a {@code repo} do.
 */
@Service
public class SourceResolver {

    /** Skip a re-fetch if we fetched this repo+branch within this window (bounds fetches to ~1/request). */
    private static final long FETCH_THROTTLE_MS = 2000;

    /** Startup fallback token from {@code bitbucket.token} (application.yml / IntelliJ run config). */
    private final String propertyToken;
    /** Machine-wide settings — the config menu's live Bitbucket token overrides the property. May be null in tests. */
    private final SettingsService settings;
    private final Path workDir;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Long> lastFetch = new ConcurrentHashMap<>();
    // A recent failure is remembered briefly so the several resolve() calls a single request makes
    // don't each re-attempt a clone that just failed (which, for an unreachable host, would multiply
    // the timeout). It clears on success and expires after the throttle window, so a fixed config
    // (e.g. a corrected token) is retried on the next request.
    private final Map<String, Long> lastFailure = new ConcurrentHashMap<>();
    private final Map<String, String> lastFailureMsg = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public SourceResolver(@Value("${bitbucket.token:}") String token,
                          @Value("${bitbucket.work-dir:}") String workDir,
                          SettingsService settings) {
        this.propertyToken = token == null ? "" : token.trim();
        this.settings = settings;
        this.workDir = (workDir == null || workDir.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "traceguard-repos")
                : Path.of(workDir.trim());
    }

    /** Convenience for tests / local-only use (no token, default work dir, no settings store). */
    public SourceResolver(String token) {
        this(token, (String) null);
    }

    /** Convenience for tests / local-only use (fixed token + work dir, no settings store). */
    public SourceResolver(String token, String workDir) {
        this.propertyToken = token == null ? "" : token.trim();
        this.settings = null;
        this.workDir = (workDir == null || workDir.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "traceguard-repos")
                : Path.of(workDir.trim());
    }

    /**
     * The token to authenticate with: the live one saved via the config menu ({@link SettingsService})
     * when present, otherwise the {@code bitbucket.token} property. Read per request so a token saved in
     * the UI takes effect without a restart.
     */
    private String currentToken() {
        if (settings != null) {
            String live = settings.bitbucketToken();
            if (live != null && !live.isBlank()) {
                return live.trim();
            }
        }
        return propertyToken;
    }

    /** Clone/fetch {@code repo} at {@code ref} (branch or tag) and return the local working dir. */
    public Path resolve(String repo, String ref) {
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("Enter a Bitbucket repo URL.");
        }
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Enter a branch or tag for the Bitbucket repo.");
        }
        String url = repo.trim();
        String rev = ref.trim();
        String key = url + " " + rev;
        Long failedAt = lastFailure.get(key);
        if (failedAt != null && now() - failedAt < FETCH_THROTTLE_MS) {
            // Failed moments ago — don't re-attempt within the window (an unreachable host would
            // otherwise multiply the timeout across a request's several resolve() calls).
            throw new IllegalArgumentException(lastFailureMsg.getOrDefault(key,
                    "Bitbucket checkout failed for " + url + " @ " + rev));
        }
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(workDir);
                Path dir = workDir.resolve(safeName(url, rev));
                if (Files.isDirectory(dir.resolve(".git"))) {
                    updateExisting(dir, url, rev, key);
                } else {
                    cloneFresh(dir, url, rev, key);
                }
                lastFailure.remove(key);   // success clears any remembered failure
                return dir;
            } catch (IllegalArgumentException e) {
                recordFailure(key, e.getMessage());
                throw e;
            } catch (Exception e) {
                String root = rootMessage(e);
                LOG.warn("Bitbucket checkout failed for {} @ {}", url, rev, e);   // full cause in the server log
                String msg = "Bitbucket checkout failed for " + url + " @ " + rev + ": " + root + hintFor(root);
                recordFailure(key, msg);
                throw new IllegalArgumentException(msg);
            }
        }
    }

    private void recordFailure(String key, String msg) {
        lastFailure.put(key, now());
        if (msg != null) {
            lastFailureMsg.put(key, msg);
        }
    }

    private void cloneFresh(Path dir, String url, String rev, String key) throws Exception {
        deleteRecursively(dir);   // clear any half-written dir
        try (Git git = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir.toFile())
                .setNoCheckout(true)          // we check out the requested ref ourselves
                .setTransportConfigCallback(auth())
                .call()) {
            checkoutTo(git, rev, false);   // fresh clone (no-checkout) — must populate the working tree
        }
        lastFetch.put(key, now());
    }

    private void updateExisting(Path dir, String url, String rev, String key) throws Exception {
        try (Git git = Git.open(dir.toFile())) {
            if (now() - lastFetch.getOrDefault(key, 0L) > FETCH_THROTTLE_MS) {
                git.fetch()
                        .setRemote("origin")
                        .setRefSpecs(new RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                        .setTagOpt(TagOpt.FETCH_TAGS)
                        .setTransportConfigCallback(auth())
                        .call();
                // Only re-checkout when the ref actually advanced. A forced checkout rewrites every
                // working-tree file (bumping mtimes), which would make the scan's fingerprint change
                // and force a full re-scan on every request — so an unchanged branch stays warm.
                checkoutTo(git, rev, true);
                lastFetch.put(key, now());
            }
        }
    }

    /**
     * Detached checkout at whatever the branch / tag / sha resolves to. When {@code skipIfSame} and the
     * working tree is already at that commit, leave the files untouched (so mtimes don't change).
     */
    private void checkoutTo(Git git, String rev, boolean skipIfSame) throws Exception {
        Repository repo = git.getRepository();
        ObjectId id = resolveRev(repo, rev);
        if (id == null) {
            throw new IllegalArgumentException("Branch or tag not found in the repo: " + rev);
        }
        if (skipIfSame && id.equals(repo.resolve("HEAD"))) {
            return;   // already checked out at this commit — nothing to do, keep the working tree stable
        }
        git.checkout().setName(id.getName()).setForced(true).call();
    }

    private ObjectId resolveRev(Repository repo, String rev) throws IOException {
        for (String cand : new String[]{rev, "origin/" + rev, "refs/tags/" + rev,
                "refs/remotes/origin/" + rev, "refs/heads/" + rev}) {
            ObjectId id = repo.resolve(cand);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private TransportConfigCallback auth() {
        String token = currentToken();
        return transport -> {
            if (transport instanceof TransportHttp http && !token.isBlank()) {
                http.setAdditionalHeaders(Map.of("Authorization", "Bearer " + token));
            }
        };
    }

    // --- helpers ---

    private static long now() {
        return System.currentTimeMillis();
    }

    /** A readable, unique, filesystem-safe folder name for a repo+ref pair. */
    private static String safeName(String url, String rev) {
        String repoName = url.replaceAll("[/\\\\]+$", "");
        int slash = Math.max(repoName.lastIndexOf('/'), repoName.lastIndexOf('\\'));
        repoName = (slash >= 0 ? repoName.substring(slash + 1) : repoName).replaceAll("\\.git$", "");
        String slug = (repoName + "_" + rev).replaceAll("[^A-Za-z0-9._-]", "-");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60);
        }
        return slug + "-" + Integer.toHexString((url + " " + rev).hashCode());
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best-effort cleanup of a stale dir
                }
            });
        }
    }

    /** Turn a raw JGit failure into an actionable hint about what to fix. */
    private static String hintFor(String rootMsg) {
        String m = rootMsg == null ? "" : rootMsg.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("401") || m.contains("not authorized") || m.contains("unauthorized")
                || m.contains("authentication") || m.contains("403") || m.contains("forbidden")) {
            return "  →  Authentication failed. Save a valid Bitbucket HTTP access token (Read scope) in the "
                    + "Config menu (⚙) — it takes effect immediately — or set 'bitbucket.token' in application.yml.";
        }
        if (m.contains("pkix") || m.contains("certification path") || m.contains("certificate")
                || m.contains("ssl") || m.contains("handshake")) {
            return "  →  The Bitbucket server's TLS certificate is not trusted by the JVM. Import your internal CA "
                    + "certificate into the JDK truststore (cacerts) and restart.";
        }
        if (m.contains("unknownhost") || m.contains("unknown host") || m.contains("connect")
                || m.contains("timed out") || m.contains("timeout") || m.contains("unreachable")) {
            return "  →  Could not reach the host. Check the HTTPS repo URL, your VPN/network, and any HTTPS proxy.";
        }
        if (m.contains("not found") || m.contains("404") || m.contains("repository not found")
                || m.contains("no such") ) {
            return "  →  Repo or branch not found. Verify the HTTPS clone URL and the exact branch/tag name.";
        }
        return "";
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SourceResolver.class);

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}

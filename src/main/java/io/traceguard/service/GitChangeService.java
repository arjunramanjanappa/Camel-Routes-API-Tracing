package io.traceguard.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the files a release changed, where a "release" is identified by the app/commit version token(s) in
 * the commit message ({@code [jira][country][19.18.0]-message}, position may vary). One or more versions may
 * be given (comma/space-separated) and are matched <b>literally</b> — {@code 19.10}, {@code 19.10.0} and
 * {@code 19.10.1} are distinct (the exact Jira version used to commit). Shells out to the git CLI (like
 * {@link GitBlameService}) and degrades gracefully to "no changes" if git is missing or the source isn't a
 * work tree.
 *
 * <p>Whitespace-only changes are ignored ({@code -w}); the net effect from before the release to HEAD is
 * used, so reverts within the release don't count. Renames are followed ({@code -M}).
 *
 * <p>Every git call is a separate process (slow on Windows, slower on a scanned corporate PC), so results that
 * can never change — a file's content at a COMMIT HASH, the diff between two commit hashes — are memoised for
 * the life of the service: a second Compare of the same release (after editing the ignore list, say) re-runs
 * almost no git. Anything that can move ({@code HEAD}, the commit log, the working tree) is never memoised.
 * {@link #statsAndReset()} reports the call count and time so a slow Compare can be explained from the log.
 */
public class GitChangeService {

    private static final Logger LOG = LoggerFactory.getLogger(GitChangeService.class);
    /** A full commit hash, optionally with a parent suffix ({@code ^}, {@code ~2}) — content at it is immutable. */
    private static final Pattern IMMUTABLE_REF = Pattern.compile("[0-9a-f]{40}(\\^+|~\\d+)?");
    private static final int MEMO_MAX = 4000;
    private static final long SLOW_CALL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final Map<String, Optional<List<String>>> memo = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong memoHits = new AtomicLong();
    private final AtomicLong nanos = new AtomicLong();

    /** Git activity since the last reset: processes run, answers served from the memo, time spent in git. */
    public record Stats(long calls, long memoHits, long millis) {
    }

    /** Snapshot the counters and start again — called once per Compare so the log line covers that Compare only. */
    public Stats statsAndReset() {
        return new Stats(calls.getAndSet(0), memoHits.getAndSet(0), TimeUnit.NANOSECONDS.toMillis(nanos.getAndSet(0)));
    }

    /** True when every ref names a commit hash (so git's answer for it can never change). Null = "no ref" is fine. */
    static boolean immutable(String... refs) {
        for (String r : refs) {
            if (r != null && !IMMUTABLE_REF.matcher(r.trim()).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Files (repo-relative, forward-slashed) the release changed, how many commits matched, the distinct
     * commit authors who changed each file (for "who to ask"), and — for the per-version breakdown — which
     * of the requested version(s) changed each file.
     */
    public record ReleaseChanges(Set<String> changedFiles, int matchedCommits, boolean gitAvailable,
                                 Map<String, List<String>> fileAuthors,
                                 Map<String, List<String>> fileVersions,
                                 Set<String> deletedFiles,
                                 String baselineRef,
                                 Map<String, String> fileBeforeRef,
                                 Map<String, String> fileAfterRef,
                                 Map<String, List<String>> fileReleaseCommits,
                                 Map<String, List<CommitRef>> fileCommitRefs) {
        public static ReleaseChanges none() {
            return new ReleaseChanges(Set.of(), 0, false, Map.of(), Map.of(), Set.of(), null,
                    Map.of(), Map.of(), Map.of(), Map.of());
        }

        /** Every matched release commit's hash — to tell a blamed line that the release wrote from an older one. */
        public Set<String> commitHashes() {
            Set<String> out = new LinkedHashSet<>();
            for (List<CommitRef> refs : fileCommitRefs.values()) {
                for (CommitRef r : refs) {
                    out.add(r.hash());
                }
            }
            return out;
        }

        /** Pre-release commit-ish for THIS file (parent of the OLDEST matched commit that touched it), so a diff
         *  is bounded to the release's own commits on the file — not the whole history before it. Falls back to
         *  the global {@link #baselineRef} if the per-file span is unknown. */
        public String beforeRefFor(String file) {
            String r = fileBeforeRef.get(file);
            return r != null ? r : baselineRef;
        }

        /** Post-release commit-ish for THIS file (the NEWEST matched commit that touched it), so a diff ends at
         *  the release's last change to the file — excluding any later/working-tree edits. Falls back to
         *  {@code HEAD} if the per-file span is unknown. */
        public String afterRefFor(String file) {
            String r = fileAfterRef.get(file);
            return r != null ? r : "HEAD";
        }
    }

    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]+)\\]");
    /** A Jira key inside a bracket token of the commit subject: {@code [SGMB-4601]}. */
    private static final Pattern TICKET = Pattern.compile("\\[\\s*([A-Za-z][A-Za-z0-9]*-\\d+)\\s*\\]");

    /**
     * One release commit as the report attributes it: who, under which Jira ticket, and the message. Parsed from
     * the subject {@code [SGMB-4601][SG][19.18.0] Add qrType XN branch}: the ticket is the bracket token shaped
     * like {@code KEY-123} (null when none), the message is what follows the last bracket token.
     */
    public record CommitRef(String hash, String author, String ticket, String message) {
        public static CommitRef of(String hash, String author, String subject) {
            String s = subject == null ? "" : subject.strip();
            Matcher t = TICKET.matcher(s);
            String ticket = t.find() ? t.group(1).toUpperCase(Locale.ROOT) : null;
            int last = s.lastIndexOf(']');
            String message = (last >= 0 ? s.substring(last + 1) : s).replaceFirst("^[\\s\\-:–—]+", "").strip();
            return new CommitRef(hash, author == null ? "" : author.strip(), ticket, message);
        }
    }

    public ReleaseChanges changedFor(Path repoDir, String appVersion) {
        if (repoDir == null) {
            return ReleaseChanges.none();
        }
        // One or more versions, comma/space-separated, matched literally. Insertion order is kept so the
        // per-file version list reads in the order the user entered them.
        Set<String> wanted = parseVersions(appVersion);
        if (wanted.isEmpty()) {
            return ReleaseChanges.none();
        }

        // 1. Every commit as hash | timestamp | subject. The version token lives in the commit subject
        //    (first line), so %s keeps each commit on exactly one line — no separator gymnastics needed.
        List<String> log = run(repoDir, 15, "log", "--no-merges", "--format=%H|%ct|%s");
        if (log == null) {
            return ReleaseChanges.none();   // not a work tree / git missing
        }
        List<String> matched = new ArrayList<>();
        Map<String, List<String>> commitVersions = new LinkedHashMap<>();   // hash -> the requested version(s) it carries
        Map<String, String> commitSubjects = new LinkedHashMap<>();         // hash -> subject (ticket + message)
        // git log is newest-first (and never lists a parent before its child), so the LAST matched commit we
        // see is the oldest — a robust pre-release baseline even when several commits share a timestamp
        // (which timestamp comparison would tie-break wrongly).
        String earliest = null;
        for (String rec : log) {
            String r = rec.strip();
            if (r.isEmpty()) {
                continue;
            }
            String[] f = r.split("\\|", 3);   // hash | timestamp | subject
            if (f.length < 3) {
                continue;
            }
            List<String> vs = versionsIn(f[2], wanted);
            if (!vs.isEmpty()) {
                String hash = f[0].trim();
                matched.add(hash);
                commitVersions.put(hash, vs);
                commitSubjects.put(hash, f[2]);
                earliest = hash;   // overwritten each match → ends on the oldest matched commit
            }
        }
        if (matched.isEmpty()) {
            return new ReleaseChanges(Set.of(), 0, true, Map.of(), Map.of(), Set.of(), null,
                    Map.of(), Map.of(), Map.of(), Map.of());   // no commit matched any version
        }

        // 2. Candidate files + their authors + the version(s) that touched them: non-whitespace changes across
        //    the matched commits (one git show). The "@@@<hash>|<author>" format line precedes each commit's
        //    numstat block, so files are attributed to the matched commit (its author and its version) that
        //    changed them.
        Set<String> candidates = new LinkedHashSet<>();
        Map<String, LinkedHashSet<String>> authors = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> versions = new LinkedHashMap<>();
        // git show lists the matched commits in the order given (matched = newest-first), so the FIRST matched
        // commit we see touching a file is the newest release change to it, the LAST is the oldest. These bound
        // the diff to the release's OWN commits on each file (excludes history before, and later/uncommitted edits).
        Map<String, String> fileNewest = new LinkedHashMap<>();
        Map<String, String> fileOldest = new LinkedHashMap<>();
        // Every matched commit that touched each file (newest-first, as encountered), so a caller can diff each
        // release commit against its OWN parent and accumulate — capturing ONLY the release's changes, never an
        // unrelated commit interleaved between two release commits on the same file.
        Map<String, List<String>> fileCommits = new LinkedHashMap<>();
        // The same commits as the report attributes them (author, Jira ticket, message), per file, newest first.
        Map<String, List<CommitRef>> fileCommitRefs = new LinkedHashMap<>();
        List<String> showArgs = new ArrayList<>(List.of("show", "--format=@@@%H|%an", "-w", "-M", "--numstat"));
        showArgs.addAll(matched);
        List<String> stat = run(repoDir, 30, showArgs.toArray(new String[0]));
        if (stat != null) {
            String curAuthor = null;
            String curHash = null;
            List<String> curVersions = List.of();
            for (String line : stat) {
                if (line.startsWith("@@@")) {
                    String rest = line.substring(3);
                    int bar = rest.indexOf('|');
                    curHash = (bar >= 0 ? rest.substring(0, bar) : rest).trim();
                    curAuthor = bar >= 0 ? rest.substring(bar + 1).trim() : null;
                    curVersions = commitVersions.getOrDefault(curHash, List.of());
                    continue;
                }
                String[] c = line.split("\t");
                if (c.length < 3) {
                    continue;
                }
                boolean real = !"0".equals(c[0].trim()) || !"0".equals(c[1].trim());   // "-"/"-" = binary → changed
                if (real) {
                    String file = renamedTarget(c[2]);
                    candidates.add(file);
                    if (curAuthor != null && !curAuthor.isEmpty()) {
                        authors.computeIfAbsent(file, k -> new LinkedHashSet<>()).add(curAuthor);
                    }
                    if (!curVersions.isEmpty()) {
                        versions.computeIfAbsent(file, k -> new LinkedHashSet<>()).addAll(curVersions);
                    }
                    if (curHash != null) {
                        fileNewest.putIfAbsent(file, curHash);   // first seen (newest-first order) = newest touch
                        fileOldest.put(file, curHash);           // overwritten each time → ends on the oldest touch
                        List<String> fc = fileCommits.computeIfAbsent(file, k -> new ArrayList<>());
                        if (fc.isEmpty() || !fc.get(fc.size() - 1).equals(curHash)) {
                            fc.add(curHash);   // newest-first; one entry per matched commit that touched the file
                            fileCommitRefs.computeIfAbsent(file, k -> new ArrayList<>())
                                    .add(CommitRef.of(curHash, curAuthor, commitSubjects.get(curHash)));
                        }
                    }
                }
            }
        }

        // 3. Net check: keep only files that still differ (ignoring whitespace) from just before the release to HEAD.
        if (earliest != null) {
            List<String> net = run(repoDir, 15, "diff", "-w", "-M", "--name-only", earliest + "^", "HEAD");
            if (net != null) {
                Set<String> netSet = new LinkedHashSet<>();
                for (String n : net) {
                    if (!n.isBlank()) {
                        netSet.add(n.trim().replace('\\', '/'));
                    }
                }
                candidates.retainAll(netSet);
            }
        }
        // Files the release DELETED (git status 'D'), from just before the release to HEAD. A removed shared
        // @Component class / route lands here — used to flag a backward-incompatible removal, distinct from a
        // modification. Renames (status 'R') are NOT deletions and are excluded.
        Set<String> deleted = new LinkedHashSet<>();
        if (earliest != null) {
            List<String> ns = run(repoDir, 15, "diff", "-M", "--name-status", earliest + "^", "HEAD");
            if (ns != null) {
                for (String l : ns) {
                    String s = l.strip();
                    if (s.startsWith("D\t") || s.startsWith("D ")) {
                        deleted.add(s.substring(1).strip().replace('\\', '/'));
                    }
                }
            }
        }

        Map<String, List<String>> fileAuthors = new LinkedHashMap<>();
        Map<String, List<String>> fileVersions = new LinkedHashMap<>();
        for (String f : candidates) {
            LinkedHashSet<String> a = authors.get(f);
            if (a != null && !a.isEmpty()) {
                fileAuthors.put(f, new ArrayList<>(a));
            }
            LinkedHashSet<String> v = versions.get(f);
            if (v != null && !v.isEmpty()) {
                fileVersions.put(f, orderedByRequest(v, wanted));
            }
        }
        // The pre-release baseline commit-ish (parent of the release's earliest matched commit), so callers can
        // fetch a changed file's PRE-release content (git show baselineRef:path) and diff a BAU route's own XML
        // across the release — detecting an in-place modification of a route the old app still runs.
        String baselineRef = earliest != null ? earliest + "^" : null;
        // Per-file release span: <oldest-touch>^ .. <newest-touch>, so a BAU in-place diff reflects ONLY what the
        // release's own commits did to that file (a field the release added/removed), never edits from commits
        // before or after the release, nor uncommitted working-tree state.
        Map<String, String> fileBeforeRef = new LinkedHashMap<>();
        Map<String, String> fileAfterRef = new LinkedHashMap<>();
        // Per-file matched commits, CHRONOLOGICAL (oldest-first), so a caller can replay each release commit's own
        // diff in order and accumulate the net effect of ONLY the release's commits on the file.
        Map<String, List<String>> fileReleaseCommits = new LinkedHashMap<>();
        for (String f : candidates) {
            String oldest = fileOldest.get(f);
            String newest = fileNewest.get(f);
            if (oldest != null) {
                fileBeforeRef.put(f, oldest + "^");
            }
            if (newest != null) {
                fileAfterRef.put(f, newest);
            }
            List<String> cs = fileCommits.get(f);
            if (cs != null && !cs.isEmpty()) {
                List<String> chrono = new ArrayList<>(cs);
                java.util.Collections.reverse(chrono);   // newest-first → oldest-first
                fileReleaseCommits.put(f, chrono);
            }
        }
        Map<String, List<CommitRef>> refs = new LinkedHashMap<>();
        for (String f : candidates) {
            List<CommitRef> cr = fileCommitRefs.get(f);
            if (cr != null) {
                refs.put(f, List.copyOf(cr));
            }
        }
        return new ReleaseChanges(candidates, matched.size(), true, fileAuthors, fileVersions, deleted, baselineRef,
                fileBeforeRef, fileAfterRef, fileReleaseCommits, refs);
    }

    /**
     * The content of {@code relPath} at commit-ish {@code ref} ({@code git show ref:relPath}), one entry per
     * line, or null if git is missing / the file did not exist at that ref (e.g. a route added by the release).
     * Used to fetch a BAU route file's pre-release version for an in-place diff.
     */
    public List<String> fileAtRef(Path repoDir, String ref, String relPath) {
        if (repoDir == null || ref == null || relPath == null || relPath.isBlank()) {
            return null;
        }
        return run(repoDir, 15, immutable(ref), "show", ref + ":" + relPath.replace('\\', '/'));
    }

    /**
     * The ref holding {@code relPath} as it was just BEFORE {@code commit}: its graph parent ({@code commit^}).
     * The release's commits are non-merge commits, so the parent's tree is exactly the state the commit changed —
     * even when the parent itself never touched the file, the file at the parent equals the file's previous
     * version in its own history. No git call is needed (the old {@code git log -- file} walk per commit was the
     * slowest part of a Compare on a long-history repo).
     */
    public static String parentRef(String commit) {
        return commit == null || commit.isBlank() ? null : commit.trim() + "^";
    }

    /**
     * The actual changed lines a commit made to a file, whitespace ignored ({@code git diff -w fromRef toRef}) —
     * the {@code +}/{@code -} lines only, hunk headers and unchanged context dropped, so it reads as the real file
     * difference. {@code fromRef} null (the commit created the file) → the whole file shows as added.
     */
    public List<String> diffLines(Path repoDir, String fromRef, String toRef, String relPath) {
        return diffLines(repoDir, fromRef, toRef, relPath, false);
    }

    /**
     * The actual changed lines a commit made to a file. {@code withContext=false}: only the {@code +}/{@code -}
     * lines (compact). {@code withContext=true}: a few unchanged lines around each change (git {@code -U3}) plus
     * the {@code @@} hunk locators, so the reviewer can see WHERE in the file a change sits.
     */
    public List<String> diffLines(Path repoDir, String fromRef, String toRef, String relPath, boolean withContext) {
        if (repoDir == null || toRef == null || relPath == null || relPath.isBlank()) {
            return List.of();
        }
        String path = relPath.replace('\\', '/');
        // Match IntelliJ's "ignore whitespaces and empty lines": -w ignores whitespace inside a line,
        // --ignore-blank-lines ignores added/removed blank lines, and --ignore-cr-at-eol ignores a CR/LF vs LF
        // difference at end of line (common on Windows — otherwise EVERY line reads as changed and the whole file
        // shows). Together, a pure reformat/reindent/line-ending change produces no change lines at all.
        String unified = withContext ? "-U3" : "-U0";
        List<String> out = unifiedDiff(repoDir, fromRef, toRef, path, unified);
        if (out == null) {
            return List.of();
        }
        List<String> changes = new ArrayList<>();
        for (String l : out) {
            if (l.startsWith("+++") || l.startsWith("---")) {
                continue;   // file headers — not content
            }
            if (l.startsWith("@@")) {
                if (withContext) {
                    changes.add(l);   // hunk locator (@@ -a,b +c,d @@) — shows where in the file
                }
                continue;
            }
            if ((l.startsWith("+") || l.startsWith("-")) && !l.substring(1).trim().isEmpty()) {
                changes.add(l);   // keep the +/- prefix so the UI can colour add vs remove; skip blank/ws-only lines
            } else if (withContext && l.startsWith(" ")) {
                changes.add(l);   // a surrounding context line (unchanged) — kept only for the with-context view
            }
        }
        return changes;
    }

    /** Changed line spans of a file across a diff: 1-based, inclusive {@code [start,end]} pairs, per side. */
    public record LineRanges(List<int[]> oldRanges, List<int[]> newRanges) {
    }

    private static final Pattern HUNK =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    /**
     * The line spans a diff changed in {@code relPath}, as 1-based inclusive {@code [start,end]} pairs on the OLD
     * and NEW side. Reads only the {@code @@} hunk headers of a {@code -U0} diff (whitespace / blank-line / CR
     * differences ignored, exactly like {@link #diffLines}), so no per-line counting is needed. A pure addition
     * contributes only a new-side span; a pure deletion only an old-side span. Returns null when git is missing
     * or the diff could not be produced — the caller then treats the change as undetermined.
     */
    public LineRanges changedLineRanges(Path repoDir, String fromRef, String toRef, String relPath) {
        if (repoDir == null || toRef == null || relPath == null || relPath.isBlank()) {
            return null;
        }
        String path = relPath.replace('\\', '/');
        List<String> out = unifiedDiff(repoDir, fromRef, toRef, path, "-U0");
        if (out == null) {
            return null;
        }
        List<int[]> oldR = new ArrayList<>();
        List<int[]> newR = new ArrayList<>();
        for (String l : out) {
            Matcher m = HUNK.matcher(l);
            if (!m.find()) {
                continue;
            }
            int oStart = Integer.parseInt(m.group(1));
            int oCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            int nStart = Integer.parseInt(m.group(3));
            int nCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
            if (oCount > 0) {
                oldR.add(new int[]{oStart, oStart + oCount - 1});
            }
            if (nCount > 0) {
                newR.add(new int[]{nStart, nStart + nCount - 1});
            }
        }
        return new LineRanges(oldR, newR);
    }

    /** Split the field into the distinct version tokens the user entered (comma/whitespace-separated), trimmed. */
    static Set<String> parseVersions(String field) {
        Set<String> out = new LinkedHashSet<>();
        if (field == null) {
            return out;
        }
        for (String part : field.trim().split("[,\\s]+")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /** The requested version(s) that appear as a literal {@code [token]} in the commit subject, in subject order. */
    private static List<String> versionsIn(String subject, Set<String> wanted) {
        List<String> found = new ArrayList<>();
        Matcher m = BRACKET.matcher(subject);
        while (m.find()) {
            String tok = m.group(1).trim();
            if (wanted.contains(tok) && !found.contains(tok)) {
                found.add(tok);   // literal, exact match: 19.10 != 19.10.0 != 19.10.1
            }
        }
        return found;
    }

    /** Order the versions of one file by the order the user requested them (stable, readable badges). */
    private static List<String> orderedByRequest(Set<String> got, Set<String> wanted) {
        List<String> out = new ArrayList<>();
        for (String w : wanted) {
            if (got.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /** numstat rename forms: {@code old => new} or {@code dir/{old => new}/file} → the new path (forward-slashed). */
    private static String renamedTarget(String path) {
        String p = path.trim();
        int lb = p.indexOf('{');
        int rb = p.indexOf('}');
        if (lb >= 0 && rb > lb && p.substring(lb, rb).contains(" => ")) {
            String mid = p.substring(lb + 1, rb);
            String newMid = mid.substring(mid.indexOf(" => ") + 4);
            p = (p.substring(0, lb) + newMid + p.substring(rb + 1)).replace("//", "/");
        } else {
            int arrow = p.indexOf(" => ");
            if (arrow >= 0) {
                p = p.substring(arrow + 4);
            }
        }
        return p.trim().replace('\\', '/');
    }

    /** The whitespace-insensitive unified diff of one file between two refs ({@code fromRef} null = the commit
     *  that created it, shown against nothing). Memoised when both refs are commit hashes. Null when git failed. */
    private List<String> unifiedDiff(Path repoDir, String fromRef, String toRef, String path, String unified) {
        boolean memoable = immutable(fromRef, toRef);
        return (fromRef == null || fromRef.isBlank())
                ? run(repoDir, 15, memoable, "show", "-w", "--ignore-blank-lines", "--ignore-cr-at-eol", "--no-color", unified, "--format=", toRef, "--", path)
                : run(repoDir, 15, memoable, "diff", "-w", "--ignore-blank-lines", "--ignore-cr-at-eol", "--no-color", unified, fromRef, toRef, "--", path);
    }

    private List<String> run(Path dir, int timeoutSec, String... gitArgs) {
        return run(dir, timeoutSec, false, gitArgs);
    }

    /**
     * Run git and return stdout lines (null = failed / timed out). {@code memoable} = the answer can never change
     * (every ref is a commit hash), so it is kept and served from memory next time — within this Compare and the
     * next one. The memo is bounded: it is simply emptied when it grows past {@link #MEMO_MAX} entries.
     */
    private List<String> run(Path dir, int timeoutSec, boolean memoable, String... gitArgs) {
        String key = memoable ? dir.toAbsolutePath() + "\u0000" + String.join("\u0000", gitArgs) : null;
        if (key != null) {
            Optional<List<String>> hit = memo.get(key);
            if (hit != null) {
                memoHits.incrementAndGet();
                return hit.orElse(null);
            }
        }
        long t0 = System.nanoTime();
        List<String> out = exec(dir, timeoutSec, gitArgs);
        long took = System.nanoTime() - t0;
        calls.incrementAndGet();
        nanos.addAndGet(took);
        if (took > SLOW_CALL_NANOS) {
            LOG.warn("Slow git call ({} s): git {}", TimeUnit.NANOSECONDS.toSeconds(took), String.join(" ", gitArgs));
        }
        if (key != null) {
            if (memo.size() >= MEMO_MAX) {
                memo.clear();
            }
            memo.put(key, Optional.ofNullable(out));
        }
        return out;
    }

    private static List<String> exec(Path dir, int timeoutSec, String... gitArgs) {
        try {
            List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
            cmd.addAll(List.of(gitArgs));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            List<String> out = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.add(line);
                }
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }
}

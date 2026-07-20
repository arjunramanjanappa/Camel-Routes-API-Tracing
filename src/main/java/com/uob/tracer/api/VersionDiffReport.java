package com.uob.tracer.api;

import java.util.ArrayList;
import java.util.List;

/**
 * The release-diff report: for a target client version, what every impacted API
 * changed relative to its immediate-lower version. Pure static analysis — no logs.
 */
public class VersionDiffReport {

    /** Discriminator for the UI. */
    public String getMode() { return "version-diff"; }

    private String version;
    private String country;
    /** The module's name (pom.xml artifactId, else the source folder) — for grouping multi-module analyses. */
    private String moduleName;
    /** True when the source has no versioned (R&lt;ver&gt;_) routes, so it was analysed at N/A (latest). */
    private boolean unversioned;
    private int changedCount;
    private int newCount;
    private int unchangedCount;
    /** True for the N/A snapshot: {@link #apis} are the latest/base routes in scope, NOT a diff. */
    private boolean snapshot;
    private int snapshotCount;
    /** The app/commit version whose code changes were analysed (e.g. {@code 19.18.0}); null when not requested. */
    private String appVersion;
    /** How many commits carried the app-version token — 0 means the release touched nothing (or git unavailable). */
    private int matchedCommits;
    /** APIs whose Java/route code the app-version release changed (may overlap New/Changed/Unchanged). */
    private int codeChangedCount;
    /** True when {@link #appVersion} was given but the source isn't a git work tree, so no code-change analysis ran. */
    private boolean codeChangeUnavailable;
    private final List<ApiDiff> apis = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    /** Imports/routes that could not be resolved and need a human to review (see TraceResponse). */
    private final List<String> needsReview = new ArrayList<>();

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getModuleName() { return moduleName; }
    public void setModuleName(String moduleName) { this.moduleName = moduleName; }

    public boolean isUnversioned() { return unversioned; }
    public void setUnversioned(boolean unversioned) { this.unversioned = unversioned; }

    public int getChangedCount() { return changedCount; }
    public void setChangedCount(int changedCount) { this.changedCount = changedCount; }

    public int getNewCount() { return newCount; }
    public void setNewCount(int newCount) { this.newCount = newCount; }

    public int getUnchangedCount() { return unchangedCount; }
    public void setUnchangedCount(int unchangedCount) { this.unchangedCount = unchangedCount; }

    public boolean isSnapshot() { return snapshot; }
    public void setSnapshot(boolean snapshot) { this.snapshot = snapshot; }

    public int getSnapshotCount() { return snapshotCount; }
    public void setSnapshotCount(int snapshotCount) { this.snapshotCount = snapshotCount; }

    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }

    public int getMatchedCommits() { return matchedCommits; }
    public void setMatchedCommits(int matchedCommits) { this.matchedCommits = matchedCommits; }

    public int getCodeChangedCount() { return codeChangedCount; }
    public void setCodeChangedCount(int codeChangedCount) { this.codeChangedCount = codeChangedCount; }

    public boolean isCodeChangeUnavailable() { return codeChangeUnavailable; }
    public void setCodeChangeUnavailable(boolean codeChangeUnavailable) { this.codeChangeUnavailable = codeChangeUnavailable; }

    public List<ApiDiff> getApis() { return apis; }
    public List<String> getWarnings() { return warnings; }
    public List<String> getNeedsReview() { return needsReview; }
}

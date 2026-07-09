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
    private int changedCount;
    private int newCount;
    private int unchangedCount;
    /** True for the N/A snapshot: {@link #apis} are the latest/base routes in scope, NOT a diff. */
    private boolean snapshot;
    private int snapshotCount;
    private final List<ApiDiff> apis = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    /** Imports/routes that could not be resolved and need a human to review (see TraceResponse). */
    private final List<String> needsReview = new ArrayList<>();

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

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

    public List<ApiDiff> getApis() { return apis; }
    public List<String> getWarnings() { return warnings; }
    public List<String> getNeedsReview() { return needsReview; }
}

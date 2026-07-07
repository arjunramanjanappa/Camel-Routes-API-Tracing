package com.arjun.tracer.scan;

import com.arjun.tracer.model.RouteModel;

import java.util.List;

/**
 * One scanned XML file: its routes plus its assembly metadata. {@code relPath}
 * uses {@code /} separators so import suffixes (e.g.
 * {@code META-INF/routes/security.xml}) can be matched portably.
 *
 * <p>{@code fromDependency} marks a file that came from a dependency source (a shared/core
 * library), not the primary source. Dependency files are country- and version-agnostic host/
 * shared routes, so they are always included in a country scope — see
 * {@link SourceIndex#scopedRegistry}.
 */
public record FileInfo(String relPath, List<RouteModel> routes, RouteXmlMetadata metadata,
                       boolean fromDependency) {

    /** Primary-source file (not from a dependency). */
    public FileInfo(String relPath, List<RouteModel> routes, RouteXmlMetadata metadata) {
        this(relPath, routes, metadata, false);
    }

    /** Country/scope name when this file is a bootstrap: the file name without extension. */
    public String baseName() {
        String name = relPath;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }
}

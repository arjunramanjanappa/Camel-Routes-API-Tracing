package com.arjun.tracer.scan;

import com.arjun.tracer.model.RouteModel;

import java.util.List;

/**
 * One scanned XML file: its routes plus its assembly metadata. {@code relPath}
 * uses {@code /} separators so import suffixes (e.g.
 * {@code META-INF/routes/security.xml}) can be matched portably.
 */
public record FileInfo(String relPath, List<RouteModel> routes, RouteXmlMetadata metadata) {

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

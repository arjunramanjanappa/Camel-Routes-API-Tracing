package com.arjun.tracer.loader;

import com.arjun.tracer.model.RouteModel;

import java.util.List;

/**
 * Loads the routes contained in a single XML file into the neutral
 * {@link RouteModel} form.
 */
public interface RouteModelLoader {

    /**
     * @param fileName    name of the source file (for diagnostics)
     * @param xmlContent  the raw XML
     * @return the routes found; empty if the file contains none
     * @throws Exception if the content cannot be parsed by this loader
     */
    List<RouteModel> load(String fileName, String xmlContent) throws Exception;
}

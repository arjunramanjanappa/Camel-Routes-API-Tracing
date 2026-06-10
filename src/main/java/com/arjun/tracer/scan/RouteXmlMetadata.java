package com.arjun.tracer.scan;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The assembly-level facts of one route/bootstrap XML file: which resources it
 * imports, which route contexts it references, which route contexts it defines,
 * and whether it declares a {@code <camelContext>} (i.e. it is a bootstrap such
 * as {@code SG.xml}). Used to scope analysis to a single country.
 *
 * @param imports        values of {@code <import resource="...">}
 * @param contextRefs    values of {@code <routeContextRef ref="...">}
 * @param definedContexts ids of {@code <routeContext id="...">} declared here
 * @param hasCamelContext true if a {@code <camelContext>} is present
 */
public record RouteXmlMetadata(List<String> imports, List<String> contextRefs,
                               Set<String> definedContexts, boolean hasCamelContext) {

    public static RouteXmlMetadata parse(String xml) {
        try {
            Document doc = parseSecure(xml);
            List<String> imports = attrs(doc, "import", "resource");
            List<String> refs = attrs(doc, "routeContextRef", "ref");
            Set<String> contexts = new LinkedHashSet<>(attrs(doc, "routeContext", "id"));
            boolean camelContext = doc.getElementsByTagNameNS("*", "camelContext").getLength() > 0;
            return new RouteXmlMetadata(imports, refs, contexts, camelContext);
        } catch (Exception e) {
            return new RouteXmlMetadata(List.of(), List.of(), Set.of(), false);
        }
    }

    private static List<String> attrs(Document doc, String localName, String attr) {
        List<String> out = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            String v = ((Element) nodes.item(i)).getAttribute(attr);
            if (v != null && !v.isBlank()) {
                out.add(v.trim());
            }
        }
        return out;
    }

    private static Document parseSecure(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new InputSource(new StringReader(xml)));
    }
}

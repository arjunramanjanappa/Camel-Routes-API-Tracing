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
 * @param hostRouteIds   ids of {@code <route>}s that reference {@code CamelHttpUri}
 *                       (they perform the backend HTTP call)
 */
public record RouteXmlMetadata(List<String> imports, List<String> contextRefs,
                               Set<String> definedContexts, boolean hasCamelContext,
                               Set<String> hostRouteIds) {

    private static final String HTTP_URI_MARKER = "CamelHttpUri";

    public static RouteXmlMetadata parse(String xml) {
        try {
            Document doc = parseSecure(xml);
            List<String> imports = attrs(doc, "import", "resource");
            List<String> refs = attrs(doc, "routeContextRef", "ref");
            Set<String> contexts = new LinkedHashSet<>(attrs(doc, "routeContext", "id"));
            boolean camelContext = doc.getElementsByTagNameNS("*", "camelContext").getLength() > 0;
            return new RouteXmlMetadata(imports, refs, contexts, camelContext, hostRouteIds(doc));
        } catch (Exception e) {
            return new RouteXmlMetadata(List.of(), List.of(), Set.of(), false, Set.of());
        }
    }

    /** Route ids whose subtree mentions {@code CamelHttpUri} (attribute or text). */
    private static Set<String> hostRouteIds(Document doc) {
        Set<String> ids = new LinkedHashSet<>();
        NodeList routes = doc.getElementsByTagNameNS("*", "route");
        for (int i = 0; i < routes.getLength(); i++) {
            Element route = (Element) routes.item(i);
            String id = route.getAttribute("id");
            if (id != null && !id.isBlank() && mentionsHttpUri(route)) {
                ids.add(id.trim());
            }
        }
        return ids;
    }

    private static boolean mentionsHttpUri(Element el) {
        var attributes = el.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            if (attributes.item(i).getNodeValue() != null
                    && attributes.item(i).getNodeValue().contains(HTTP_URI_MARKER)) {
                return true;
            }
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node n = children.item(i);
            if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && mentionsHttpUri((Element) n)) {
                return true;
            }
            if (n.getNodeType() == org.w3c.dom.Node.TEXT_NODE && n.getNodeValue() != null
                    && n.getNodeValue().contains(HTTP_URI_MARKER)) {
                return true;
            }
        }
        return false;
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

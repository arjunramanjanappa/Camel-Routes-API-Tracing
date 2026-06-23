package com.arjun.tracer.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Builds a generic, tag-agnostic canonicalisation of a Camel {@code <route>} body
 * and diffs two such canonicalisations. Used only by the release-diff feature; it
 * reads the RAW route XML (independent of the parsed {@link com.arjun.tracer.model.RouteModel},
 * which drops unknown tags and their attributes) so that ANY structural change a
 * release makes — a bean, a header, a setProperty, a choice branch, an endpoint —
 * is detected, not just the tags the tracer's model understands.
 *
 * <p>Canonical form, one line per element (depth-indented, document order):
 * <pre>{@code  tag attr="v" attr2="v2" : <text>}</pre>
 * Attributes are sorted by name so attribute-order churn isn't reported as a change;
 * the route's own {@code <from>} is skipped (it carries the version-bearing id and
 * would otherwise diff on every comparison).
 */
final class RouteXmlDiff {

    private RouteXmlDiff() {
    }

    /**
     * Index every {@code <route id="...">} found across the given source files to
     * its canonical body lines. Files that fail to parse are skipped (best effort).
     */
    static Map<String, List<String>> indexRouteBodies(List<Path> files) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Path p : files) {
            String name = p.getFileName() != null ? p.getFileName().toString().toLowerCase() : "";
            if (!name.endsWith(".xml")) {
                continue;
            }
            Document doc;
            try {
                doc = parse(Files.readString(p));
            } catch (Exception e) {
                continue;   // not parseable as XML, or unreadable — skip
            }
            NodeList routes = doc.getElementsByTagNameNS("*", "route");
            for (int i = 0; i < routes.getLength(); i++) {
                Element route = (Element) routes.item(i);
                String id = route.getAttribute("id");
                if (id == null || id.isEmpty()) {
                    continue;
                }
                out.putIfAbsent(id, canonicalize(route));
            }
        }
        return out;
    }

    /** The canonical body lines of a route element (its steps, excluding {@code <from>}). */
    static List<String> canonicalize(Element route) {
        List<String> lines = new ArrayList<>();
        for (Element child : childElements(route)) {
            if (local(child).equals("from")) {
                continue;   // route identity / version-bearing — not a step
            }
            appendCanonical(child, 0, lines);
        }
        return lines;
    }

    /**
     * A version-bearing route token, e.g. the {@code R9.4_} in a
     * {@code direct:R9.4_masterFundTransferSubmitApi} hand-off. Collapsed to a
     * version-agnostic placeholder so a pure version bump (every downstream
     * reference re-stamped from R9.3_ to R9.4_) is NOT reported as a change — only
     * genuine structural differences survive.
     */
    private static final Pattern VERSION_TOKEN = Pattern.compile("R\\d+(?:\\.\\d+)*_");

    private static void appendCanonical(Element el, int depth, List<String> out) {
        String line = "  ".repeat(depth) + describe(el);
        out.add(VERSION_TOKEN.matcher(line).replaceAll("R{v}_"));
        for (Element child : childElements(el)) {
            appendCanonical(child, depth + 1, out);
        }
    }

    /** {@code tag attr="v" … [: text]} — attributes sorted, leaf text appended. */
    private static String describe(Element el) {
        StringBuilder sb = new StringBuilder(local(el));
        Map<String, String> attrs = new TreeMap<>();
        NamedNodeMap map = el.getAttributes();
        for (int i = 0; i < map.getLength(); i++) {
            Node a = map.item(i);
            String n = a.getLocalName() != null ? a.getLocalName() : a.getNodeName();
            if (n.startsWith("xmlns")) {
                continue;   // namespace declarations aren't behavioural
            }
            attrs.put(n, a.getNodeValue());
        }
        attrs.forEach((n, v) -> sb.append(' ').append(n).append("=\"").append(v).append('"'));
        if (!hasElementChild(el)) {
            String text = el.getTextContent();
            if (text != null && !text.trim().isEmpty()) {
                sb.append(" : ").append(text.trim().replaceAll("\\s+", " "));
            }
        }
        return sb.toString();
    }

    /** Added (in target, not lower) and removed (in lower, not target) lines, via LCS. */
    record Diff(List<String> added, List<String> removed) {
        boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }
    }

    /**
     * Diff two canonical line lists. {@code lower} is the older (immediate-lower)
     * body, {@code target} the newer (release) body: lines only in {@code target}
     * are "added", lines only in {@code lower} are "removed".
     */
    static Diff diff(List<String> lower, List<String> target) {
        int n = lower.size();
        int m = target.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = lower.get(i).equals(target.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (lower.get(i).equals(target.get(j))) {
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                removed.add(lower.get(i++));
            } else {
                added.add(target.get(j++));
            }
        }
        while (i < n) {
            removed.add(lower.get(i++));
        }
        while (j < m) {
            added.add(target.get(j++));
        }
        return new Diff(added, removed);
    }

    // --- DOM helpers (mirrors the hardened parse in XmlDomRouteModelLoader) ---

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new InputSource(new StringReader(xml)));
    }

    private static List<Element> childElements(Node parent) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static boolean hasElementChild(Node parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    private static String local(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }
}

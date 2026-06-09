package com.arjun.tracer.loader;

import com.arjun.tracer.model.ChoiceElement;
import com.arjun.tracer.model.ContainerElement;
import com.arjun.tracer.model.RecipientListElement;
import com.arjun.tracer.model.RouteElement;
import com.arjun.tracer.model.RouteModel;
import com.arjun.tracer.model.SetPropertyElement;
import com.arjun.tracer.model.ToElement;
import com.arjun.tracer.model.WhenElement;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure DOM loader: walks the XML directly without a CamelContext.
 *
 * <p>Used as the fallback when {@link CamelRouteModelLoader} cannot load a file
 * (custom components, Spring beans, property placeholders that fail validation,
 * non-standard wrapping). It locates every {@code <route>} element regardless of
 * namespace or nesting, so it copes with route files the runtime loader rejects.
 */
public class XmlDomRouteModelLoader implements RouteModelLoader {

    /** Child element local-names that carry an expression/predicate value. */
    private static final Set<String> LANGUAGES = Set.of(
            "simple", "constant", "xpath", "jsonpath", "groovy", "header",
            "exchangeProperty", "property", "method", "spel", "el", "tokenize",
            "language", "javaScript", "mvel", "ognl", "xquery");

    @Override
    public List<RouteModel> load(String fileName, String xmlContent) throws Exception {
        Document doc = parse(xmlContent);
        List<RouteModel> routes = new ArrayList<>();
        NodeList routeNodes = doc.getElementsByTagNameNS("*", "route");
        for (int i = 0; i < routeNodes.getLength(); i++) {
            Element routeEl = (Element) routeNodes.item(i);
            routes.add(toRouteModel(routeEl));
        }
        return routes;
    }

    private Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // Harden against XXE — we never need external entities or DTDs here.
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new InputSource(new StringReader(xml)));
    }

    private RouteModel toRouteModel(Element routeEl) {
        String routeId = attr(routeEl, "id");
        String fromUri = null;
        for (Element child : childElements(routeEl)) {
            if (local(child).equals("from")) {
                fromUri = attr(child, "uri");
                break;
            }
        }
        if (routeId == null && fromUri != null) {
            routeId = fromUri; // best-effort identity when id omitted
        }
        List<RouteElement> elements = parseChildren(routeEl);
        return new RouteModel(routeId, fromUri, elements, "dom");
    }

    /** Parse the processor steps that are direct children of {@code parent}. */
    private List<RouteElement> parseChildren(Element parent) {
        List<RouteElement> out = new ArrayList<>();
        for (Element el : childElements(parent)) {
            RouteElement parsed = parseElement(el);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out;
    }

    private RouteElement parseElement(Element el) {
        String name = local(el);
        switch (name) {
            case "from":
                return null; // handled at route level
            case "to":
            case "toD":
            case "wireTap":
            case "enrich":
            case "pollEnrich":
                return new ToElement(attr(el, "uri"));
            case "recipientList":
                return new RecipientListElement(expressionText(el));
            case "setProperty":
            case "setExchangeProperty":
                return new SetPropertyElement(attr(el, "name"), expressionText(el));
            case "choice":
                return parseChoice(el);
            case "when":
            case "otherwise":
                return null; // only meaningful inside choice
            default:
                // Unknown processor: keep its endpoint reference if any, and
                // descend so nested to/setProperty/choice are not lost.
                String uri = attr(el, "uri");
                if (uri != null) {
                    return new ToElement(uri);
                }
                return new ContainerElement(name, parseChildren(el));
        }
    }

    private ChoiceElement parseChoice(Element choiceEl) {
        List<WhenElement> whens = new ArrayList<>();
        List<RouteElement> otherwise = new ArrayList<>();
        for (Element child : childElements(choiceEl)) {
            String n = local(child);
            if (n.equals("when")) {
                whens.add(parseWhen(child));
            } else if (n.equals("otherwise")) {
                otherwise.addAll(parseChildren(child));
            }
        }
        return new ChoiceElement(whens, otherwise);
    }

    private WhenElement parseWhen(Element whenEl) {
        List<Element> children = childElements(whenEl);
        String predicate = null;
        List<RouteElement> steps = new ArrayList<>();
        boolean predicateConsumed = false;
        for (Element child : children) {
            if (!predicateConsumed && LANGUAGES.contains(local(child))) {
                predicate = text(child);
                predicateConsumed = true;
                continue;
            }
            RouteElement parsed = parseElement(child);
            if (parsed != null) {
                steps.add(parsed);
            }
        }
        return new WhenElement(predicate, steps);
    }

    /**
     * Extract an expression/predicate value: prefer a nested language element's
     * text, then an {@code expression} attribute, then the element's own text.
     */
    private String expressionText(Element el) {
        for (Element child : childElements(el)) {
            if (LANGUAGES.contains(local(child))) {
                return text(child);
            }
        }
        String exprAttr = attr(el, "expression");
        if (exprAttr != null) {
            return exprAttr;
        }
        return text(el);
    }

    // --- small DOM helpers ---

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

    private static String local(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    private static String attr(Element el, String name) {
        String v = el.getAttribute(name);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static String text(Element el) {
        String t = el.getTextContent();
        return t == null ? null : t.trim();
    }
}

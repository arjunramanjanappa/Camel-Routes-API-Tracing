package com.uob.tracer.loader;

import com.uob.tracer.model.ChoiceElement;
import com.uob.tracer.model.ContainerElement;
import com.uob.tracer.model.RecipientListElement;
import com.uob.tracer.model.RouteElement;
import com.uob.tracer.model.RouteModel;
import com.uob.tracer.model.SetPropertyElement;
import com.uob.tracer.model.ToElement;
import com.uob.tracer.model.WhenElement;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.ChoiceDefinition;
import org.apache.camel.model.FromDefinition;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.OtherwiseDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RecipientListDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.SetPropertyDefinition;
import org.apache.camel.model.ToDefinition;
import org.apache.camel.model.ToDynamicDefinition;
import org.apache.camel.model.WhenDefinition;
import org.apache.camel.model.language.ExpressionDefinition;
import org.apache.camel.spi.Resource;
import org.apache.camel.spi.RoutesLoader;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ResourceHelper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Loads route XML using Camel 4's own {@link RoutesLoader}, then walks the
 * resulting {@link RouteDefinition} / {@link ProcessorDefinition} model.
 *
 * <p>This is the spec-preferred path: the flow is reconstructed from Camel's
 * runtime route model rather than from hand-rolled XML walking. The
 * {@code CamelContext} is never started — we only need the parsed definitions.
 *
 * <p>The Camel {@code xml-io-dsl} loader only recognises a top-level
 * {@code <routes>}/{@code <route>}. Real frameworks often wrap routes in Spring
 * {@code <beans>} → {@code <camelContext>}/{@code <routeContext>}. For those we
 * first try the content as-is, and if that yields nothing we extract the
 * {@code <route>} elements into a synthetic {@code <routes>} document and retry
 * — so the RouteDefinition path still applies. Anything we still cannot load is
 * handled by {@link XmlDomRouteModelLoader}.
 */
public class CamelRouteModelLoader implements RouteModelLoader {

    private static final String CAMEL_NS = "http://camel.apache.org/schema/spring";

    @Override
    public List<RouteModel> load(String fileName, String xmlContent) throws Exception {
        // 1. Try the file as-is (plain <routes>/<route> documents).
        List<RouteModel> routes = attempt(xmlContent);
        if (!routes.isEmpty()) {
            return routes;
        }
        // 2. Unwrap <beans>/<camelContext>/<routeContext> wrappers and retry.
        String unwrapped = unwrapToRoutesDocument(xmlContent);
        if (unwrapped != null) {
            List<RouteModel> rewrapped = attempt(unwrapped);
            if (!rewrapped.isEmpty()) {
                return rewrapped;
            }
        }
        return routes; // empty — caller falls back to the DOM loader
    }

    /** One load attempt against a fresh (never started) context; empty on failure. */
    private List<RouteModel> attempt(String xmlContent) {
        // Constant single-dot name: Camel derives the loader from the extension
        // after the FIRST dot, so a real name like "R9.4_x.xml" would be misread.
        try (DefaultCamelContext ctx = new DefaultCamelContext()) {
            RoutesLoader loader = PluginHelper.getRoutesLoader(ctx);
            Resource resource = ResourceHelper.fromString("inline-route.xml", xmlContent);
            Collection<RoutesBuilder> builders = loader.findRoutesBuilders(resource);
            for (RoutesBuilder rb : builders) {
                rb.addRoutesToCamelContext(ctx);
            }
            List<RouteModel> result = new ArrayList<>();
            for (RouteDefinition def : ((ModelCamelContext) ctx).getRouteDefinitions()) {
                result.add(toRouteModel(def));
            }
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * If the document is not already a {@code <routes>} doc but contains
     * {@code <route>} elements (e.g. inside {@code <beans>}/{@code <camelContext>}/
     * {@code <routeContext>}), copy those routes into a fresh Camel-namespaced
     * {@code <routes>} document. Returns null when there is nothing to rewrap.
     */
    private String unwrapToRoutesDocument(String xml) {
        try {
            Document doc = parseSecure(xml);
            Element root = doc.getDocumentElement();
            if (root != null && "routes".equals(localName(root))) {
                return null; // already a routes document — nothing to do
            }
            NodeList routeNodes = doc.getElementsByTagNameNS("*", "route");
            if (routeNodes.getLength() == 0) {
                return null;
            }
            Document out = newDocument();
            Element routesEl = out.createElementNS(CAMEL_NS, "routes");
            out.appendChild(routesEl);
            for (int i = 0; i < routeNodes.getLength(); i++) {
                routesEl.appendChild(copyNormalized(out, (Element) routeNodes.item(i)));
            }
            return serialize(out);
        } catch (Exception e) {
            return null;
        }
    }

    /** Deep-copy an element into the Camel namespace (normalising any other/empty ns). */
    private Element copyNormalized(Document doc, Element src) {
        Element e = doc.createElementNS(CAMEL_NS, localName(src));
        NamedNodeMap attrs = src.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String name = a.getNodeName();
            if (name.equals("xmlns") || name.startsWith("xmlns:")) {
                continue; // drop namespace declarations
            }
            String local = a.getLocalName() != null ? a.getLocalName() : name;
            e.setAttribute(local, a.getNodeValue());
        }
        NodeList children = src.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                e.appendChild(copyNormalized(doc, (Element) n));
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                String text = n.getNodeValue();
                if (text != null && !text.isBlank()) {
                    e.appendChild(doc.createTextNode(text));
                }
            }
        }
        return e;
    }

    private Document parseSecure(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new InputSource(new StringReader(xml)));
    }

    private Document newDocument() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().newDocument();
    }

    private String serialize(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    private static String localName(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    private RouteModel toRouteModel(RouteDefinition def) {
        String routeId = def.getRouteId();
        FromDefinition from = def.getInput();
        String fromUri = from != null ? from.getEndpointUri() : null;
        if (routeId == null) {
            routeId = fromUri;
        }
        List<RouteElement> elements = convertOutputs(def.getOutputs());
        return new RouteModel(routeId, fromUri, elements, "camel");
    }

    private List<RouteElement> convertOutputs(List<ProcessorDefinition<?>> outputs) {
        List<RouteElement> out = new ArrayList<>();
        if (outputs == null) {
            return out;
        }
        for (ProcessorDefinition<?> def : outputs) {
            RouteElement el = convert(def);
            if (el != null) {
                out.add(el);
            }
        }
        return out;
    }

    private RouteElement convert(ProcessorDefinition<?> def) {
        if (def instanceof ToDefinition to) {
            return new ToElement(to.getEndpointUri());
        }
        if (def instanceof ToDynamicDefinition toD) {
            return new ToElement(toD.getUri());
        }
        if (def instanceof RecipientListDefinition<?> rl) {
            return new RecipientListElement(expr(rl.getExpression()));
        }
        if (def instanceof SetPropertyDefinition sp) {
            return new SetPropertyElement(propertyName(sp), expr(sp.getExpression()));
        }
        if (def instanceof ChoiceDefinition choice) {
            return convertChoice(choice);
        }
        // Generic processor: keep its kind and descend into any children.
        return new ContainerElement(shortName(def), convertOutputs(def.getOutputs()));
    }

    private ChoiceElement convertChoice(ChoiceDefinition choice) {
        List<WhenElement> whens = new ArrayList<>();
        for (WhenDefinition when : choice.getWhenClauses()) {
            whens.add(new WhenElement(expr(when.getExpression()), convertOutputs(when.getOutputs())));
        }
        OtherwiseDefinition otherwise = choice.getOtherwise();
        List<RouteElement> otherwiseSteps =
                otherwise != null ? convertOutputs(otherwise.getOutputs()) : List.of();
        return new ChoiceElement(whens, otherwiseSteps);
    }

    private static String propertyName(SetPropertyDefinition sp) {
        try {
            return sp.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String expr(ExpressionDefinition expression) {
        if (expression == null) {
            return null;
        }
        String text = expression.getExpression();
        return text != null ? text : expression.toString();
    }

    private static String shortName(ProcessorDefinition<?> def) {
        try {
            return def.getShortName();
        } catch (Throwable t) {
            return def.getClass().getSimpleName();
        }
    }
}

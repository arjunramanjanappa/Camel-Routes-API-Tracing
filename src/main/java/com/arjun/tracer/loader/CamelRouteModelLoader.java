package com.arjun.tracer.loader;

import com.arjun.tracer.model.ChoiceElement;
import com.arjun.tracer.model.ContainerElement;
import com.arjun.tracer.model.RecipientListElement;
import com.arjun.tracer.model.RouteElement;
import com.arjun.tracer.model.RouteModel;
import com.arjun.tracer.model.SetPropertyElement;
import com.arjun.tracer.model.ToElement;
import com.arjun.tracer.model.WhenElement;
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
 * Files this loader cannot parse are handled by {@link XmlDomRouteModelLoader}.
 */
public class CamelRouteModelLoader implements RouteModelLoader {

    @Override
    public List<RouteModel> load(String fileName, String xmlContent) throws Exception {
        // Use a constant, single-dot name: Camel picks the RoutesBuilderLoader by
        // the extension after the FIRST dot, so a real name like "R9.4_x.xml"
        // would be misread as extension "4_x.xml". The content is what matters.
        String location = "inline-route.xml";
        try (DefaultCamelContext ctx = new DefaultCamelContext()) {
            RoutesLoader loader = PluginHelper.getRoutesLoader(ctx);
            Resource resource = ResourceHelper.fromString(location, xmlContent);
            Collection<RoutesBuilder> builders = loader.findRoutesBuilders(resource);
            for (RoutesBuilder rb : builders) {
                rb.addRoutesToCamelContext(ctx);
            }
            List<RouteModel> result = new ArrayList<>();
            for (RouteDefinition def : ((ModelCamelContext) ctx).getRouteDefinitions()) {
                result.add(toRouteModel(def));
            }
            return result;
        }
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

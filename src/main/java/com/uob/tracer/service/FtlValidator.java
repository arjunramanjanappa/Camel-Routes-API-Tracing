package com.uob.tracer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import freemarker.core.ParseException;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateBooleanModel;
import freemarker.template.TemplateCollectionModel;
import freemarker.template.TemplateHashModelEx;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModel;
import freemarker.template.TemplateModelIterator;
import freemarker.template.TemplateNumberModel;
import freemarker.template.TemplateScalarModel;
import freemarker.template.TemplateSequenceModel;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validates an impacted {@code .ftl} request-body template in the Release Impact diff. Two checks:
 * <ol>
 *   <li><b>FTL syntax</b> — the template is parsed with FreeMarker's real parser, so an unclosed / mismatched
 *       directive or a malformed {@code ${..}} is reported with line/column (a {@code ParseException}).</li>
 *   <li><b>JSON structure</b> — since these templates are contractually JSON, the parsed template is rendered
 *       with a permissive stub data model (every variable resolves; every {@code <#if>} is true; every
 *       {@code <#list>} runs once) and the OUTPUT is parsed as JSON. A failure (e.g. a missing comma, a
 *       trailing comma, an unbalanced brace) is reported with the JSON error message.</li>
 * </ol>
 *
 * <p>Because a template's JSON validity can legitimately depend on runtime data (a conditional trailing
 * comma), a structure finding is a <b>soft warning</b> ("verify"), not a hard failure — it catches a real
 * static defect while a rare data-dependent case is just a prompt to check. FTL parse errors are unambiguous.
 */
public final class FtlValidator {

    /** kind = {@code SYNTAX} (FTL parse) or {@code STRUCTURE} (rendered output isn't valid JSON). */
    public record Issue(String kind, String message, int line) {
    }

    private FtlValidator() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static List<Issue> validate(String name, String content) {
        List<Issue> issues = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return issues;
        }
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_33);
        cfg.setLocale(Locale.ROOT);
        cfg.setNumberFormat("computer");            // ${n} -> "1" (no locale grouping)
        cfg.setBooleanFormat("true,false");         // ${b} -> true/false (valid JSON)
        cfg.setLogTemplateExceptions(false);
        // Keep directive-only lines as blank lines instead of removing them, so the rendered output keeps the
        // SAME line numbering as the template — a JSON error's line then maps to the source line. (It can
        // still drift AFTER a <#list> whose body spans lines, since the body repeats; noted in the finding.)
        cfg.setWhitespaceStripping(false);
        // Keep rendering through a stub-model hiccup (an odd built-in) so we still get full output to JSON-parse.
        cfg.setTemplateExceptionHandler((te, env, out) -> {
            try {
                out.write("null");
            } catch (Exception ignore) {
                // best-effort placeholder
            }
        });
        // Static analysis of a repo template: never allow ?new to instantiate arbitrary classes.
        cfg.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);

        Template tpl;
        try {
            tpl = new Template(name, new StringReader(content), cfg);
        } catch (ParseException pe) {
            issues.add(new Issue("SYNTAX", firstLine(pe.getMessage()), pe.getLineNumber()));
            return issues;   // an unparseable template can't be rendered
        } catch (Exception e) {
            issues.add(new Issue("SYNTAX", firstLine(e.getMessage()), 0));
            return issues;
        }

        String rendered;
        try {
            StringWriter sw = new StringWriter();
            tpl.process(new AnyModel(), sw);
            rendered = sw.toString();
        } catch (Exception e) {
            // The handler swallows per-expression errors; a hard failure here is rare — don't manufacture a
            // structure finding from it (it may be a stub-model limitation, not a template defect).
            return issues;
        }

        if (!rendered.isBlank()) {
            try {
                JSON.readTree(rendered);
            } catch (com.fasterxml.jackson.core.JsonProcessingException je) {
                int line = je.getLocation() != null ? je.getLocation().getLineNr() : 0;
                int col = je.getLocation() != null ? je.getLocation().getColumnNr() : 0;
                String msg = "rendered output is not valid JSON — " + firstLine(je.getOriginalMessage());
                if (col > 0) {
                    msg += " (col " + col + ")";
                }
                issues.add(new Issue("STRUCTURE", msg, Math.max(line, 0)));
            } catch (Exception e) {
                issues.add(new Issue("STRUCTURE", "rendered output is not valid JSON — " + firstLine(e.getMessage()), 0));
            }
        }
        return issues;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        String one = nl >= 0 ? s.substring(0, nl) : s;
        return one.length() > 240 ? one.substring(0, 237) + "..." : one.trim();
    }

    /**
     * A permissive FreeMarker data model: it answers ANY access so a template renders end-to-end for
     * validation — as a hash it returns itself (so {@code a.b.c} resolves), as a sequence it yields one
     * element (itself), and as a scalar/number/boolean/method it gives a JSON-safe placeholder.
     */
    private static final class AnyModel implements TemplateHashModelEx, TemplateScalarModel, TemplateBooleanModel,
            TemplateNumberModel, TemplateSequenceModel, TemplateCollectionModel, TemplateMethodModelEx {

        @Override public TemplateModel get(String key) { return this; }          // hash: any property -> self
        @Override public boolean isEmpty() { return false; }
        @Override public int size() { return 1; }                                // hash & sequence size
        @Override public TemplateCollectionModel keys() { return this; }
        @Override public TemplateCollectionModel values() { return this; }
        @Override public String getAsString() { return "1"; }                    // ${x} -> 1 (valid quoted/unquoted)
        @Override public boolean getAsBoolean() { return true; }                 // <#if x> -> true
        @Override public Number getAsNumber() { return 1; }
        @Override public TemplateModel get(int index) { return this; }           // sequence element -> self
        @Override public Object exec(List arguments) { return this; }            // x.method(..) -> self
        @Override public TemplateModelIterator iterator() {
            return new TemplateModelIterator() {
                private boolean done;
                @Override public TemplateModel next() { done = true; return AnyModel.this; }
                @Override public boolean hasNext() { return !done; }             // <#list x as i> runs once
            };
        }
    }
}

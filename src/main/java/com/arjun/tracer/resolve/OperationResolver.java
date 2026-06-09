package com.arjun.tracer.resolve;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves an API path to its operation name by parsing controller sources with
 * JavaParser. The operation name is the handler <em>method</em> name (e.g.
 * {@code fundTransferSubmitV2Api}); the framework routes on that exact value.
 *
 * <p>Source-based (not reflection-based) because the tracer is pointed at a
 * source directory, not a running JVM with the controllers on the classpath.
 */
public class OperationResolver {

    private static final Map<String, String> HTTP_METHODS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH");

    private final List<OperationInfo> operations = new ArrayList<>();

    /** Parse one Java source file and record any mapped endpoints it declares. */
    public void addSource(String content) {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(content);
        } catch (Exception e) {
            return; // not parseable Java — ignore
        }
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String base = mappingPath(cls.getAnnotations());
            for (MethodDeclaration method : cls.getMethods()) {
                String httpMethod = httpMethod(method.getAnnotations());
                if (httpMethod == null) {
                    continue; // not a request handler
                }
                String path = join(base, mappingPath(method.getAnnotations()));
                String command = commandName(method.getAnnotations());
                operations.add(new OperationInfo(
                        normalize(path), method.getNameAsString(),
                        command, httpMethod, cls.getNameAsString()));
            }
        }
    }

    /** Best match for an API path; null if none recorded. */
    public OperationInfo resolve(String apiPath) {
        String target = normalize(apiPath);
        for (OperationInfo op : operations) {
            if (op.path().equals(target)) {
                return op;
            }
        }
        return null;
    }

    public List<OperationInfo> all() {
        return operations;
    }

    // --- annotation parsing ---

    /** First request-mapping HTTP method on a member, or null if none present. */
    private String httpMethod(List<AnnotationExpr> annotations) {
        for (AnnotationExpr ann : annotations) {
            String name = ann.getNameAsString();
            String simple = name.substring(name.lastIndexOf('.') + 1);
            if (HTTP_METHODS.containsKey(simple)) {
                return HTTP_METHODS.get(simple);
            }
            if (simple.equals("RequestMapping")) {
                return requestMappingMethod(ann);
            }
        }
        return null;
    }

    private String requestMappingMethod(AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr normal) {
            for (var pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("method")) {
                    String v = pair.getValue().toString();
                    int dot = v.lastIndexOf('.');
                    return dot >= 0 ? v.substring(dot + 1) : v;
                }
            }
        }
        return "REQUEST";
    }

    /** Extract the path from a mapping annotation's {@code value}/{@code path}. */
    private String mappingPath(List<AnnotationExpr> annotations) {
        for (AnnotationExpr ann : annotations) {
            String name = ann.getNameAsString();
            String simple = name.substring(name.lastIndexOf('.') + 1);
            boolean isMapping = simple.equals("RequestMapping") || HTTP_METHODS.containsKey(simple);
            if (!isMapping) {
                continue;
            }
            if (ann instanceof SingleMemberAnnotationExpr single) {
                return literal(single.getMemberValue());
            }
            if (ann instanceof NormalAnnotationExpr normal) {
                for (var pair : normal.getPairs()) {
                    String pn = pair.getNameAsString();
                    if (pn.equals("value") || pn.equals("path")) {
                        return literal(pair.getValue());
                    }
                }
            }
        }
        return "";
    }

    private String commandName(List<AnnotationExpr> annotations) {
        for (AnnotationExpr ann : annotations) {
            String name = ann.getNameAsString();
            String simple = name.substring(name.lastIndexOf('.') + 1);
            if (!simple.equals("CommandHandler")) {
                continue;
            }
            if (ann instanceof SingleMemberAnnotationExpr single) {
                return literal(single.getMemberValue());
            }
            if (ann instanceof NormalAnnotationExpr normal) {
                for (var pair : normal.getPairs()) {
                    if (pair.getNameAsString().equals("command")) {
                        return literal(pair.getValue());
                    }
                }
            }
        }
        return null;
    }

    /** Resolve a string literal, taking the first element of an array literal. */
    private String literal(Expression expr) {
        if (expr instanceof StringLiteralExpr s) {
            return s.getValue();
        }
        if (expr instanceof ArrayInitializerExpr array) {
            Optional<Expression> first = array.getValues().stream().findFirst();
            return first.map(this::literal).orElse("");
        }
        return expr.toString();
    }

    // --- path helpers ---

    private String join(String base, String path) {
        String a = base == null ? "" : base.trim();
        String b = path == null ? "" : path.trim();
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return (a.endsWith("/") ? a.substring(0, a.length() - 1) : a)
                + (b.startsWith("/") ? b : "/" + b);
    }

    static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        if (p.isEmpty()) {
            return "";
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        p = p.replaceAll("/{2,}", "/");
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}

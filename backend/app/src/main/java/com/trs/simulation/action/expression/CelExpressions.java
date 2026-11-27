package com.trs.simulation.action.expression;

import com.trs.simulation.common.Json;
import dev.cel.common.CelOptions;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure CEL evaluation over JSON values only, with bounded parsing, iteration and cache. @author czhcc-trsdsj\czhcc */
public final class CelExpressions {
    private static final CelOptions OPTIONS = CelOptions.current()
            .maxExpressionCodePointSize(2048)
            .maxParseRecursionDepth(32)
            .comprehensionMaxIterations(2048)
            .maxRegexProgramSize(256)
            .build();
    private static final CelCompiler COMPILER = CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(OPTIONS)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addVar("target", SimpleType.DYN)
            .addVar("params", SimpleType.DYN)
            .addVar("value", SimpleType.DYN)
            .addVar("entities", ListType.create(SimpleType.DYN))
            .addVar("relations", ListType.create(SimpleType.DYN))
            .addVar("version", SimpleType.INT)
            .addVar("initial", SimpleType.DYN)
            .addVar("metrics", SimpleType.DYN)
            .addVar("pendingEvents", SimpleType.INT)
            .build();
    private static final CelRuntime RUNTIME =
            CelRuntimeFactory.standardCelRuntimeBuilder().setOptions(OPTIONS).build();
    private static final CelCompiler RULE_COMPILER = CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(OPTIONS)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addVar("event", SimpleType.DYN)
            .addVar("entities", ListType.create(SimpleType.DYN))
            .addVar("relations", ListType.create(SimpleType.DYN))
            .addVar("version", SimpleType.INT)
            .build();
    private static final Map<String, CelRuntime.Program> CACHE = new LinkedHashMap<>(256, .75f, true);
    private static final Map<String, CelRuntime.Program> RULE_CACHE = new LinkedHashMap<>(256, .75f, true);

    private CelExpressions() {}

    /** Compiles and type-checks an expression without executing it. @author czhcc-trsdsj\czhcc */
    public static synchronized void validate(String expression) {
        program(expression);
    }

    /** Rejects statically non-boolean conditions while dynamic results remain runtime-checked. @author czhcc-trsdsj\czhcc */
    public static synchronized void validateBoolean(String expression) {
        validate(expression);
        try {
            var type = COMPILER.compile(expression).getAst().getResultType();
            if (!type.equals(SimpleType.BOOL) && !type.equals(SimpleType.DYN))
                throw new IllegalArgumentException("CEL conditions must return boolean");
        } catch (dev.cel.common.CelValidationException e) {
            throw new IllegalArgumentException("Invalid CEL condition");
        }
    }

    private static synchronized CelRuntime.Program program(String expression) {
        return program(expression, COMPILER, CACHE);
    }

    private static synchronized CelRuntime.Program program(
            String expression, CelCompiler compiler, Map<String, CelRuntime.Program> cache) {
        if (expression == null || expression.isBlank() || expression.length() > 2048)
            throw new IllegalArgumentException("CEL expression required, maximum 2048 characters");
        try {
            var cached = cache.get(expression);
            if (cached != null) return cached;
            var compiled = RUNTIME.createProgram(compiler.compile(expression).getAst());
            if (cache.size() >= 256) cache.remove(cache.keySet().iterator().next());
            cache.put(expression, compiled);
            return compiled;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CEL expression: " + e.getMessage());
        }
    }

    /** Evaluates bounded JSON input with no host methods, services or IO bindings. @author czhcc-trsdsj\czhcc */
    public static Object evaluate(String expression, Map<String, Object> variables) {
        return evaluate(program(expression), variables);
    }

    /** Validates a rule expression in its isolated declaration environment. @author czhcc-trsdsj\codexsandboxoffline */
    public static void validateRule(String expression, boolean condition) {
        program(expression, RULE_COMPILER, RULE_CACHE);
        if (condition) {
            try {
                var type = RULE_COMPILER.compile(expression).getAst().getResultType();
                if (!SimpleType.BOOL.equals(type) && !SimpleType.DYN.equals(type))
                    throw new IllegalArgumentException("CEL conditions must return boolean");
            } catch (dev.cel.common.CelValidationException e) {
                throw new IllegalArgumentException("Invalid CEL condition", e);
            }
        }
    }

    /** Evaluates a bounded, projected rule event and graph. @author czhcc-trsdsj\codexsandboxoffline */
    public static Object evaluateRule(String expression, Map<String, Object> variables) {
        return evaluate(program(expression, RULE_COMPILER, RULE_CACHE), variables);
    }

    private static Object evaluate(CelRuntime.Program program, Map<String, Object> variables) {
        if (Json.write(variables).length() > 250000) throw new IllegalArgumentException("CEL input budget exceeded");
        try {
            return program.eval(variables);
        } catch (Exception e) {
            throw new IllegalArgumentException("CEL evaluation failed: " + e.getMessage());
        }
    }
}

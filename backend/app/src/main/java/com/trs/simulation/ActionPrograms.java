package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.util.*;

/** Built-in declarative programs and validation shared by editing, publishing and execution. @author czhcc-trsdsj\czhcc */
public final class ActionPrograms {
  private ActionPrograms() {}
  private static final Map<String, Set<String>> OPERATIONS = Map.of(
      "SET_PROPERTY", Set.of("object", "property", "value"),
      "DISABLE_RELATIONS", Set.of("source", "target", "relationType"),
      "TRANSFER_MONEY", Set.of("source", "target", "amount", "currency"));

  /** Preserves narrowed historical bindings while adapting registered effects into CEL. @author czhcc-trsdsj\czhcc */
  public static Ontology upgrade(String domain, Ontology ontology) {
    if (ontology == null) return ontology(domain);
    if (ontology.version() == 3) return ontology;
    if (!domain.equals("information")) throw new IllegalArgumentException("Unsupported bank ontology");
    var old = InformationOntology.upgrade(ontology);
    InformationOntology.validate(old);
    return new Ontology(3, old.types().stream().map(t -> new TypeDefinition("ENTITY", t.type(),
        t.actions().stream().map(ActionPrograms::convert).toList())).toList());
  }

  /** Returns the current object-owned sample programs for both supported domains. @author czhcc-trsdsj\czhcc */
  public static Ontology ontology(String domain) {
    if (domain.equals("information")) return upgrade(domain, InformationOntology.definition());
    if (!domain.equals("bank")) throw new IllegalArgumentException("Unsupported action domain");
    var none = schema(Map.of());
    var transfer = new ActionDefinition("TRANSFER", "模拟转账", "Simulated transfer", "以整数分结算模拟转账，生成交易记录并保持资金守恒。",
        schema(Map.of("sourceId", reference("转出账户"), "amount", Map.of("type", "string", "description", "转账金额（元），最多两位有效小数"), "currency", Map.of("type", "string", "description", "三位币种代码，例如 CNY"))), List.of(), "TRANSFER_MONEY",
        new ActionProgram(1, List.of(), List.of(op("TRANSFER_MONEY", "source", "params.sourceId", "target", "target.id", "amount", "params.amount", "currency", "params.currency")),
            Map.of("sourceId", "entities.filter(e, e.type == 'BANK_ACCOUNT' && e.id != target.id && e.properties.currency == target.properties.currency).map(e, e.id)")));
    var flag = new ActionDefinition("FLAG_RISK", "标记模拟风险", "Flag simulated risk", "标记当前账户存在模拟风险，不改变余额。", none, List.of(), "FLAG_RISK",
        new ActionProgram(1, List.of(condition("!has(target.properties.riskFlag) || target.properties.riskFlag != true", "该账户已标记风险", "Account is already flagged")),
            List.of(set("riskFlag", "true"), set("riskEvidence", "'SIMULATED'")), Map.of()));
    var analyze = new ActionDefinition("ANALYZE_ACCOUNT", "分析账户", "Analyze account", "只读分析账户，不改变资金。", none, List.of(), "READ_ONLY", new ActionProgram(1, List.of(), List.of(), Map.of()));
    var score = new ActionDefinition("ASSESS_RISK_CEL", "CEL 风险评分", "CEL risk score", "演示 CEL 计算：余额达到五百万元记 80 分，否则记 20 分，仅为模拟评分。", none, List.of(), "CEL_RISK_SCORE",
        new ActionProgram(1, List.of(condition("!has(target.properties.riskScore)", "当前账户已有模拟评分", "Account already has a simulated risk score")),
            List.of(set("riskScore", "target.properties.balanceMinor >= 500000000 ? 80 : 20"), set("riskEvidence", "'SIMULATED'")), Map.of()));
    return new Ontology(3, List.of(new TypeDefinition("ENTITY", "BANK_ACCOUNT", List.of(transfer, flag, analyze, score)), new TypeDefinition("ENTITY", "TRANSACTION", List.of())));
  }

  private static ActionDefinition convert(ActionDefinition a) {
    var conditions = new ArrayList<ActionCondition>();
    var operations = new ArrayList<ActionOperation>();
    var options = new LinkedHashMap<String, String>();
    switch (a.effect()) {
      case "SET_REVIEWED" -> {
        conditions.add(condition("!has(target.properties.reviewed) || target.properties.reviewed != true", "此对象已审查", "Object is already reviewed"));
        operations.add(set("reviewed", "true")); operations.add(set("reviewEvidence", "'SIMULATED'"));
      }
      case "EXPOSE_ENTITY" -> {
        conditions.add(condition("!has(target.properties.exposed) || target.properties.exposed != true", "此对象已模拟暴露", "Object is already simulated exposed"));
        options.put("sourceId", "entities.filter(e, (e.type == 'DOCUMENT' || (has(e.properties.exposed) && e.properties.exposed == true)) && relations.exists(r, r.enabled && r.sourceId == e.id && r.targetId == target.id)).map(e, e.id)");
        operations.add(set("exposed", "true")); operations.add(set("exposureEvidence", "'SIMULATED'")); operations.add(set("exposureSource", "params.sourceId"));
      }
      case "REVOKE_SHARING_WITH", "DISABLE_SYNC_TO" -> {
        boolean sharing = a.effect().equals("REVOKE_SHARING_WITH");
        String parameter = sharing ? "recipientId" : "destinationId", type = sharing ? "SHARED_WITH" : "SYNCS_TO";
        options.put(parameter, "relations.filter(r, r.enabled && r.sourceId == target.id && r.relationType == '" + type + "').map(r, r.targetId)");
        operations.add(op("DISABLE_RELATIONS", "source", "target.id", "target", "params." + parameter, "relationType", "'" + type + "'"));
      }
      case "READ_ONLY" -> { }
      default -> throw new IllegalArgumentException("Unsupported legacy action effect");
    }
    var fields = new LinkedHashMap<String, Object>();
    ((Map<?, ?>) a.parametersSchema().get("properties")).forEach((key, value) -> {
      var field = new LinkedHashMap<String, Object>(); ((Map<?, ?>) value).forEach((k, v) -> field.put(k.toString(), v));
      field.put("description", switch (key.toString()) {
        case "sourceId" -> "传播来源对象 ID：当前对象通过启用的直接关系从该对象模拟接收信息。";
        case "recipientId" -> "共享接收对象 ID：由当前人员撤销向该对象的共享。";
        case "destinationId" -> "同步目标对象 ID：由当前系统停止向该对象的同步。";
        default -> field.getOrDefault("description", key.toString());
      });
      field.put("format", "entity-id"); fields.put(key.toString(), field);
    });
    String description = switch (a.effect()) {
      case "SET_REVIEWED" -> "记录模拟审查，执行引擎将当前对象标记为已审查；结论填写在行动理由中。";
      case "EXPOSE_ENTITY" -> "沿启用的直接关系，模拟当前对象从选定来源接收信息；结果不代表真实泄密。";
      case "REVOKE_SHARING_WITH" -> "由当前人员撤销向选定接收对象的共享，执行引擎禁用对应出向共享关系。";
      case "DISABLE_SYNC_TO" -> "由当前系统停止向选定对象的同步，执行引擎禁用对应出向同步关系。";
      default -> "只读分析当前对象的关系，不修改图谱；路径可达不代表已经发生泄密。";
    };
    return new ActionDefinition(a.actionType(), a.nameZh(), a.nameEn(), description, schema(fields), a.preconditions(), a.effect(), new ActionProgram(1, conditions, operations, options));
  }

  /** Validates all bindings without executing CEL or changing graph data. @author czhcc-trsdsj\czhcc */
  public static void validate(Ontology ontology) {
    require(ontology != null && ontology.version() == 3 && ontology.types() != null && ontology.types().size() <= 100, "Declarative ontology version 3 required");
    var types = new HashSet<String>();
    for (var t : ontology.types()) {
      require(t != null && "ENTITY".equals(t.targetKind()) && t.type() != null && types.add(t.type()) && t.actions() != null && t.actions().size() <= 30, "Invalid object action binding");
      var ids = new HashSet<String>();
      for (var a : t.actions()) { validate(a); require(ids.add(a.actionType()), "Duplicate action binding"); }
    }
  }

  /** Rejects unsupported operations, oversized definitions and invalid CEL before publication. @author czhcc-trsdsj\czhcc */
  public static void validate(ActionDefinition a) {
    require(a != null && a.actionType() != null && a.actionType().matches("[A-Z][A-Z0-9_]{0,63}") && !a.actionType().equals("END"), "Invalid business action ID");
    require(a.nameZh() != null && !a.nameZh().isBlank() && a.nameZh().length() <= 100 && a.nameEn() != null && !a.nameEn().isBlank() && a.nameEn().length() <= 100, "Chinese and English action names required");
    require(a.description() != null && a.description().length() <= 2000 && a.effect() != null && a.effect().length() <= 100 && a.preconditions() != null, "Invalid action description");
    require(a.parametersSchema() != null && "object".equals(a.parametersSchema().get("type")) && a.parametersSchema().get("properties") instanceof Map && Json.write(a).length() <= 20000, "Invalid or oversized action schema");
    require(Set.of("type", "properties", "required", "additionalProperties").containsAll(a.parametersSchema().keySet()), "Unsupported parameter schema keyword");
    var fields = (Map<?, ?>) a.parametersSchema().get("properties");
    require(fields.size() <= 10 && Boolean.FALSE.equals(a.parametersSchema().get("additionalProperties")), "At most 10 declared parameters, no extra properties");
    for (var e : fields.entrySet()) require(e.getKey().toString().matches("[a-zA-Z][a-zA-Z0-9_]{0,49}") && e.getValue() instanceof Map<?, ?> f && f.get("type") != null && Set.of("string", "integer", "number", "boolean").contains(f.get("type")), "Unsupported parameter type");
    for (var field : fields.values()) {
      var f = (Map<?, ?>) field;
      require(Set.of("type", "description", "format", "enum").containsAll(f.keySet()), "Unsupported parameter constraint");
      require(!f.containsKey("description") || f.get("description") instanceof String s && s.length() <= 1000, "Invalid parameter explanation");
      require(!f.containsKey("format") || ("entity-id".equals(f.get("format")) && "string".equals(f.get("type"))), "Only string entity-id references are supported");
      if (f.containsKey("enum")) require(f.get("enum") instanceof List<?> values && !values.isEmpty() && values.size() <= 100 && values.stream().allMatch(v -> parameterType(f.get("type"), v)), "Invalid parameter enum");
    }
    Object required = a.parametersSchema().getOrDefault("required", List.of());
    require(required instanceof List<?> names && fields.keySet().containsAll(names) && new HashSet<>(names).size() == names.size(), "Invalid required parameters");
    var p = a.program();
    require(p != null && p.version() == 1 && p.conditions() != null && p.conditions().size() <= 8 && p.operations() != null && p.operations().size() <= 16 && p.parameterOptions() != null && fields.keySet().containsAll(p.parameterOptions().keySet()), "Invalid declarative program");
    for (var c : p.conditions()) {
      require(c != null && c.messageZh() != null && !c.messageZh().isBlank() && c.messageEn() != null && !c.messageEn().isBlank(), "Condition explanation required");
      CelExpressions.validateBoolean(c.expression());
    }
    for (var op : p.operations()) {
      require(op != null && op.op() != null && OPERATIONS.containsKey(op.op()) && op.arguments() != null && op.arguments().keySet().equals(OPERATIONS.get(op.op())), "Unsupported primitive or arguments");
      op.arguments().values().forEach(CelExpressions::validate);
    }
    p.parameterOptions().values().forEach(CelExpressions::validate);
    for (var name : p.parameterOptions().keySet()) require("string".equals(((Map<?, ?>) fields.get(name)).get("type")), "Object options require string parameters");
  }

  private static boolean parameterType(Object type, Object value) {
    return switch (String.valueOf(type)) {
      case "string" -> value instanceof String s && !s.isBlank() && s.length() <= 1000;
      case "boolean" -> value instanceof Boolean;
      case "integer" -> value instanceof Number n && Double.isFinite(n.doubleValue()) && new java.math.BigDecimal(n.toString()).stripTrailingZeros().scale() <= 0;
      case "number" -> value instanceof Number n && Double.isFinite(n.doubleValue());
      default -> false;
    };
  }

  private static Map<String, Object> reference(String description) { return Map.of("type", "string", "format", "entity-id", "description", description); }
  private static Map<String, Object> schema(Map<String, ?> fields) { return Map.of("type", "object", "properties", fields, "required", fields.keySet().stream().sorted().toList(), "additionalProperties", false); }
  private static ActionCondition condition(String expression, String zh, String en) { return new ActionCondition(expression, zh, en); }
  private static ActionOperation set(String property, String value) { return op("SET_PROPERTY", "object", "target.id", "property", "'" + property + "'", "value", value); }
  private static ActionOperation op(String name, String... pairs) {
    var args = new LinkedHashMap<String, String>(); for (int i = 0; i < pairs.length; i += 2) args.put(pairs[i], pairs[i + 1]);
    return new ActionOperation(name, args);
  }
  private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
}

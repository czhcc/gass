package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.math.BigDecimal;
import java.util.*;

/** Domain-independent outcome evaluation against frozen initial data and declared CEL predicates. @author czhcc-trsdsj\codexsandboxoffline */
public final class BusinessEvaluation {
  private BusinessEvaluation() {}
  /** Builds trustworthy aggregates without interpreting domain names or action IDs. @author czhcc-trsdsj\codexsandboxoffline */
  public static Map<String,Object> metrics(State initial, State current, long unhandled) {
    return Map.of("totals", totals(current), "initialTotals", totals(initial), "unhandledInputs", unhandled);
  }
  private static Map<String,Object> totals(State state) {
    var sums = new TreeMap<String,BigDecimal>();
    for (var e : state.entities()) for (var entry : e.properties().entrySet()) if (entry.getValue() instanceof Number number) sums.merge(e.type() + "." + entry.getKey(), new BigDecimal(number.toString()), BigDecimal::add);
    var result = new TreeMap<String,Object>(); sums.forEach((key,value) -> { try { result.put(key, value.longValueExact()); } catch (ArithmeticException ex) { result.put(key, value.doubleValue()); } }); return result;
  }
  /** Evaluates all conditions once at the horizon and records expression errors separately. @author czhcc-trsdsj\codexsandboxoffline */
  public static BusinessOutcome evaluate(Scenario scenario, State initial, State current, int round, long pending, long unhandled) {
    var vars = HierarchyData.variables(initial, current, metrics(initial, current, unhandled), pending);
    var conditions = new ArrayList<ConditionResult>();
    for (var condition : scenario.team().conditions()) {
      Object evidence = null;
      String evidenceError = null;
      if (condition.evidenceExpression() != null && !condition.evidenceExpression().isBlank()) {
        try {
          evidence = CelExpressions.evaluate(condition.evidenceExpression(), vars);
          if (Json.write(evidence).length() > 3000) throw new IllegalArgumentException("Evidence limit exceeded");
        } catch (IllegalArgumentException e) { evidence = null; evidenceError = "Evidence calculation failed or returned too much data"; }
      }
      try {
        Object value = CelExpressions.evaluate(condition.expression(), vars);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("Condition did not return boolean");
        conditions.add(new ConditionResult(condition.id(), condition.nameZh(), condition.nameEn(), Boolean.TRUE.equals(value), "", evidence, evidenceError));
      } catch (IllegalArgumentException e) { conditions.add(new ConditionResult(condition.id(), condition.nameZh(), condition.nameEn(), false, "CEL evaluation failed; check the condition and its data dependencies", evidence, evidenceError)); }
    }
    String result = conditions.isEmpty() ? "NO_CONDITIONS" : conditions.stream().anyMatch(c -> !c.error().isEmpty()) ? "ERROR" : conditions.stream().allMatch(ConditionResult::satisfied) ? "SUCCEEDED" : "FAILED";
    return new BusinessOutcome(result, round, current.version(), List.copyOf(conditions));
  }
}

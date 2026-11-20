package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.util.*;

/** JSON-only CEL bindings shared by profiles, mock policies and business evaluation. @author czhcc-trsdsj\codexsandboxoffline */
public final class HierarchyData {
  private HierarchyData() {}
  /** Converts records and numeric JSON values without exposing Java objects to CEL. @author czhcc-trsdsj\codexsandboxoffline */
  public static Object json(Object value) { return numbers(Json.read(Json.write(value), Object.class)); }
  private static Object numbers(Object value) {
    if (value instanceof Map<?, ?> m) { var result = new LinkedHashMap<String,Object>(); m.forEach((k,v) -> result.put(k.toString(), numbers(v))); return result; }
    if (value instanceof List<?> l) return l.stream().map(HierarchyData::numbers).toList();
    return value instanceof Integer || value instanceof Short || value instanceof Byte ? ((Number) value).longValue() : value;
  }
  /** Builds a bounded environment over explicitly supplied, already authorized states. @author czhcc-trsdsj\codexsandboxoffline */
  public static Map<String,Object> variables(State initial, State current, Map<String,Object> metrics, long pending) {
    var result = new HashMap<String,Object>();
    result.put("entities", json(current.entities())); result.put("relations", json(current.relations())); result.put("version", current.version());
    result.put("initial", json(initial)); result.put("metrics", json(metrics)); result.put("pendingEvents", pending);
    result.put("target", Map.of()); result.put("params", Map.of()); return result;
  }
}

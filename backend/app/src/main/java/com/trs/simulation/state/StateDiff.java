package com.trs.simulation.state;

import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.State;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;

/** Computes a deterministic diff indexed by graph identity. @author czhcc-trsdsj\codexsandboxoffline */
public final class StateDiff {
    private StateDiff() {}

    /** A changed field with presence recorded independently of its value. @author czhcc-trsdsj\codexsandboxoffline */
    public record FieldChange(String code, boolean beforePresent, Object before, boolean afterPresent, Object after) {}

    /** A single entity or relation change. @author czhcc-trsdsj\codexsandboxoffline */
    public record Change(String kind, String id, String operation, List<FieldChange> fields) {}

    /** Compares the final graph with the original, ignoring clock and numeric representation. @author czhcc-trsdsj\codexsandboxoffline */
    public static List<Change> between(State before, State after) {
        var changes = new ArrayList<Change>();
        compare(
                "ENTITY",
                rows(before.entities(), Entity::id, StateDiff::fields),
                rows(after.entities(), Entity::id, StateDiff::fields),
                changes);
        compare(
                "RELATION",
                rows(before.relations(), Relation::id, StateDiff::fields),
                rows(after.relations(), Relation::id, StateDiff::fields),
                changes);
        return List.copyOf(changes);
    }

    /** Compares JSON-like values recursively, using numerical equality and exact key presence. @author czhcc-trsdsj\codexsandboxoffline */
    public static boolean sameValue(Object before, Object after) {
        if (before instanceof Number a && after instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        if (before instanceof Map<?, ?> a && after instanceof Map<?, ?> b) {
            return a.keySet().equals(b.keySet())
                    && a.entrySet().stream().allMatch(entry -> sameValue(entry.getValue(), b.get(entry.getKey())));
        }
        if (before instanceof List<?> a && after instanceof List<?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!sameValue(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(before, after);
    }

    private static <T> Map<String, Map<String, Object>> rows(
            List<T> values, Function<T, String> id, Function<T, Map<String, Object>> fields) {
        var rows = new LinkedHashMap<String, Map<String, Object>>();
        for (var value : values) {
            if (rows.putIfAbsent(id.apply(value), fields.apply(value)) != null) {
                throw new IllegalArgumentException("Duplicate graph identity");
            }
        }
        return rows;
    }

    private static Map<String, Object> fields(Entity entity) {
        var fields = properties(entity.properties());
        fields.put("type", entity.type());
        fields.put("name", entity.name());
        return fields;
    }

    private static Map<String, Object> fields(Relation relation) {
        var fields = properties(relation.properties());
        fields.put("sourceId", relation.sourceId());
        fields.put("targetId", relation.targetId());
        fields.put("relationType", relation.relationType());
        fields.put("enabled", relation.enabled());
        fields.put("evidence", relation.evidence());
        fields.put("source", relation.source());
        return fields;
    }

    private static Map<String, Object> properties(Map<String, Object> values) {
        var fields = new LinkedHashMap<String, Object>();
        values.forEach((code, value) -> fields.put("properties." + code, value));
        return fields;
    }

    private static void compare(
            String kind,
            Map<String, Map<String, Object>> before,
            Map<String, Map<String, Object>> after,
            List<Change> changes) {
        var ids = new TreeSet<>(before.keySet());
        ids.addAll(after.keySet());
        for (String id : ids) {
            var left = before.getOrDefault(id, Map.of());
            var right = after.getOrDefault(id, Map.of());
            var codes = new TreeSet<>(left.keySet());
            codes.addAll(right.keySet());
            var fields = new ArrayList<FieldChange>();
            for (String code : codes) {
                boolean had = left.containsKey(code), has = right.containsKey(code);
                if (had != has || !sameValue(left.get(code), right.get(code))) {
                    fields.add(new FieldChange(code, had, left.get(code), has, right.get(code)));
                }
            }
            if (!fields.isEmpty()) {
                changes.add(new Change(
                        kind,
                        id,
                        !before.containsKey(id) ? "CREATED" : !after.containsKey(id) ? "REMOVED" : "UPDATED",
                        List.copyOf(fields)));
            }
        }
    }
}

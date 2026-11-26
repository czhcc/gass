package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Outcome;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.common.Json;
import com.trs.simulation.state.OntologyConstraints;
import com.trs.simulation.state.StateDiff;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits final graph state, audit, and the durable command receipt atomically. @author czhcc-trsdsj\codexsandboxoffline */
final class ActionCommitService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RunStateRepository store;
    private final EventClockScheduler eventClock;
    private final DurableReasoning reasoning;

    /** An Action request whose selector and original payload are stable on retry. @author czhcc-trsdsj\codexsandboxoffline */
    record Command(
            String runId,
            String commandId,
            String actorId,
            long expectedVersion,
            Decision request,
            String origin,
            String taskId,
            String inputEventId) {
        String fingerprint() {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("actorId", actorId);
            fields.put("decision", request);
            fields.put("inputEventId", inputEventId);
            // A worker may resend its original decision with the version returned in the receipt.
            if (taskId == null) {
                fields.put("expectedVersion", expectedVersion);
            }
            return Json.write(fields);
        }
    }

    /** The exact state and tool response captured when the command finished. @author czhcc-trsdsj\codexsandboxoffline */
    record Receipt(State state, Map<String, Object> details) {}

    /** Stored receipt envelope and its immutable snapshot reference. @author czhcc-trsdsj\codexsandboxoffline */
    private record StoredReceipt(String requestJson, String receiptJson, long version, long clock) {}

    ActionCommitService(
            JdbcTemplate db,
            TransactionTemplate tx,
            RunStateRepository store,
            EventClockScheduler eventClock,
            DurableReasoning reasoning) {
        this.db = db;
        this.tx = tx;
        this.store = store;
        this.eventClock = eventClock;
        this.reasoning = reasoning;
    }

    Receipt retry(Command command) {
        var rows = db.query(
                "SELECT request_json,receipt_json,state_version,simulation_time FROM simulation_action_receipt"
                        + " WHERE run_id=? AND origin=? AND command_id=?",
                (rs, index) -> new StoredReceipt(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)),
                command.runId(),
                command.origin(),
                command.commandId());
        if (rows.isEmpty()) {
            return null;
        }
        var stored = rows.getFirst();
        if (!Objects.equals(Json.read(stored.requestJson(), Map.class), Json.read(command.fingerprint(), Map.class))) {
            throw new IllegalStateException("Idempotency key already used for another Action");
        }
        var snapshot = store.replay(command.runId(), stored.version());
        var state =
                new State(command.runId(), stored.clock(), stored.version(), snapshot.entities(), snapshot.relations());
        return new Receipt(state, Json.read(stored.receiptJson(), Map.class));
    }

    Receipt commit(Command command, Decision decision, State current, Outcome outcome) {
        return tx.execute(status -> {
            var prior = retry(command);
            if (prior != null) {
                return prior;
            }
            String id = command.runId();
            if (store.state(id).version() != current.version()) {
                throw new IllegalStateException("World version conflict");
            }
            boolean accepted = outcome.accepted();
            var graph = accepted ? outcome.state() : current;
            var diff = StateDiff.between(current, graph);
            var next = diff.isEmpty()
                    ? current
                    : new State(
                            id, current.simulationTime(), current.version() + 1, graph.entities(), graph.relations());
            int stateChangeSequence = 0;
            if (!diff.isEmpty()) {
                OntologyConstraints.validateCandidate(store.frozenWorld(id), current, next);
                int updated = db.update(
                        "UPDATE simulation_run SET state_json=? WHERE id=?"
                                + " AND JSON_EXTRACT(state_json,'$.version')=?",
                        Json.write(next),
                        id,
                        current.version());
                if (updated != 1) {
                    throw new IllegalStateException("World version conflict");
                }
                db.update("INSERT INTO simulation_snapshot VALUES (?,?,?)", id, next.version(), Json.write(next));
            }
            var meta = metadata(command, decision, current, next, diff, outcome);
            append(
                    command,
                    current,
                    "USER_INTERVENTION".equals(command.origin()) ? "USER_INTERVENTION" : "AGENT_DECISION",
                    Map.of(
                            "decision",
                            decision,
                            "evidence",
                            "HYPOTHESIS",
                            "origin",
                            "USER_INTERVENTION".equals(command.origin()) ? "USER" : "AGENT"),
                    meta);
            append(command, current, "ACTION_PROPOSED", Map.of("action", decision), meta);
            String targetId = decision.targetId() == null ? "" : decision.targetId();
            String actionType = decision.actionType() == null ? "" : decision.actionType();
            var appliedRelations =
                    outcome.accepted() && !diff.isEmpty() ? outcome.affectedRelationIds() : java.util.List.of();
            append(
                    command,
                    current,
                    accepted ? "ACTION_ACCEPTED" : "ACTION_REJECTED",
                    Map.of(
                            "code",
                            outcome.code(),
                            "affectedRelationIds",
                            appliedRelations,
                            "targetKind",
                            targetKind(decision, current),
                            "reason",
                            outcome.message(),
                            "actionType",
                            actionType,
                            "targetId",
                            targetId),
                    meta);
            int verdictSequence = store.eventCount(id);
            if (!diff.isEmpty()) {
                var changed = new LinkedHashMap<String, Object>(Map.of(
                        "before",
                        current,
                        "after",
                        next,
                        "version",
                        next.version(),
                        "evidence",
                        "SIMULATED",
                        "stateDiff",
                        diff));
                if (command.taskId() != null) {
                    changed.put("inputEventId", command.inputEventId() == null ? "" : command.inputEventId());
                }
                append(command, current, "WORLD_STATE_CHANGED", changed, meta);
                stateChangeSequence = store.eventCount(id);
            }
            var receipt = new LinkedHashMap<>(meta);
            receipt.put("accepted", accepted);
            receipt.put("code", outcome.code());
            receipt.put("message", outcome.message());
            receipt.put("actionType", actionType);
            receipt.put("targetId", targetId);
            receipt.put(
                    "parameters",
                    command.request().parameters() == null
                            ? Map.of()
                            : command.request().parameters());
            receipt.put("affectedRelationIds", appliedRelations);
            if (command.inputEventId() != null) {
                receipt.put("inputEventId", command.inputEventId());
            }
            if (command.taskId() != null) {
                store.append(id, "ACTION_TASK_RECEIPT", current.simulationTime(), command.actorId(), receipt);
                if (accepted && command.inputEventId() != null) {
                    store.append(
                            id,
                            "ACTION_INPUT_HANDLED",
                            current.simulationTime(),
                            command.actorId(),
                            Map.of(
                                    "taskId",
                                    command.taskId(),
                                    "inputEventId",
                                    command.inputEventId(),
                                    "outputVersion",
                                    next.version()));
                }
            }
            if (store.scenario(id).eventDriven()) {
                if (accepted && command.taskId() != null && command.inputEventId() != null) {
                    eventClock.acknowledge(
                            id,
                            command.inputEventId(),
                            stateChangeSequence == 0 ? store.eventCount(id) : stateChangeSequence);
                }
                if (!diff.isEmpty()) {
                    eventClock.enqueueDomainEvent(
                            id, current.simulationTime(), (String) meta.get("actionRunId"), next.version());
                }
            }
            db.update(
                    "INSERT INTO simulation_action_receipt VALUES (?,?,?,?,?,?,?,?)",
                    id,
                    command.origin(),
                    command.commandId(),
                    meta.get("actionRunId"),
                    command.fingerprint(),
                    Json.write(receipt),
                    next.version(),
                    next.simulationTime());
            if (store.scenario(id).eventDriven()) {
                reasoning.recordAction(
                        command,
                        decision,
                        targetKind(decision, current),
                        stateChangeSequence == 0 ? verdictSequence : stateChangeSequence,
                        receipt);
            }
            // Return the same JSON representations on first response and every subsequent retry.
            return new Receipt(Json.read(Json.write(next), State.class), Json.read(Json.write(receipt), Map.class));
        });
    }

    private Map<String, Object> metadata(
            Command command,
            Decision decision,
            State before,
            State after,
            java.util.List<StateDiff.Change> diff,
            Outcome outcome) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("runId", command.runId());
        meta.put("actionRunId", UUID.randomUUID().toString());
        meta.put("commandId", command.commandId());
        meta.put("actorId", command.actorId());
        meta.put("actionOrigin", command.origin());
        meta.put("inputVersion", before.version());
        meta.put("outputVersion", after.version());
        if (command.taskId() != null) {
            meta.put("taskId", command.taskId());
        }
        var entityIds = before.entities().stream().map(Entity::id).collect(Collectors.toSet());
        var related = new TreeSet<String>();
        addRelated(decision.targetId(), entityIds, related);
        addRelated(decision.parameters(), entityIds, related);
        addRelated(command.request().parameters(), entityIds, related);
        diff.stream().filter(change -> change.kind().equals("ENTITY")).forEach(change -> related.add(change.id()));
        var relations = diff.stream()
                .filter(change -> change.kind().equals("RELATION"))
                .map(StateDiff.Change::id)
                .collect(Collectors.toSet());
        relations.addAll(outcome.affectedRelationIds());
        if (decision.targetId() != null) {
            relations.add(decision.targetId());
        }
        before.relations().stream()
                .filter(relation -> relations.contains(relation.id()))
                .forEach(relation -> {
                    related.add(relation.sourceId());
                    related.add(relation.targetId());
                });
        meta.put("relatedIds", java.util.List.copyOf(related));
        return meta;
    }

    private void addRelated(Object value, Set<String> ids, Set<String> related) {
        if (value instanceof String id && ids.contains(id)) {
            related.add(id);
        } else if (value instanceof Map<?, ?> fields) {
            fields.values().forEach(nested -> addRelated(nested, ids, related));
        } else if (value instanceof Collection<?> values) {
            values.forEach(nested -> addRelated(nested, ids, related));
        }
    }

    private void append(
            Command command, State current, String type, Map<String, Object> event, Map<String, Object> meta) {
        var payload = new LinkedHashMap<>(meta);
        payload.putAll(event);
        store.append(command.runId(), type, current.simulationTime(), command.actorId(), payload);
    }

    String targetKind(Decision decision, State state) {
        if (decision.targetKind() != null) {
            return decision.targetKind();
        }
        if ("END".equals(decision.actionType())) {
            return "AGENT";
        }
        return state.entities().stream().anyMatch(e -> e.id().equals(decision.targetId())) ? "ENTITY" : "RELATION";
    }
}

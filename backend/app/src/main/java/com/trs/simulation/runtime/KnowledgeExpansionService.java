package com.trs.simulation.runtime;

import com.trs.simulation.common.Json;
import com.trs.simulation.knowledge.ExpansionPolicy;
import com.trs.simulation.knowledge.ExternalSubgraph;
import com.trs.simulation.knowledge.KnowledgeGraphProvider;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Query;
import com.trs.simulation.knowledge.KnowledgeProviders;
import com.trs.simulation.knowledge.SubgraphImport;
import com.trs.simulation.knowledge.SubgraphImport.Identities;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 持久外部候选与确认导入，不在运行锁或写事务中调用外部数据源。 @author czhcc-trsdsj\codexsandboxoffline */
@Service
public class KnowledgeExpansionService {
    private final RunService runs;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RunStateRepository store;
    private final KnowledgeProviders providers;

    /** 装配已在服务器注册的只读 Provider。 @author czhcc-trsdsj\codexsandboxoffline */
    public KnowledgeExpansionService(
            RunService runs, JdbcTemplate db, TransactionTemplate tx, KnowledgeProviders providers) {
        this.runs = runs;
        this.db = db;
        this.tx = tx;
        this.store = new RunStateRepository(db);
        this.providers = providers;
    }

    /** 单个运行和操作人专用的只读候选。 @author czhcc-trsdsj\codexsandboxoffline */
    public record Candidate(String id, String providerCode, long inputVersion, ExternalSubgraph batch) {}

    /** 独立于 Action 的数据导入收据，带字段来源和分币种导入增量。 @author czhcc-trsdsj\codexsandboxoffline */
    public record Receipt(
            String receiptId,
            String providerCode,
            String sourceVersion,
            String fetchedAt,
            int mappingRevision,
            long eventSequence,
            long simulationTime,
            long stateVersion,
            boolean changed,
            ExternalSubgraph.Coverage coverage,
            Map<String, Set<String>> changedFields,
            List<String> conflicts,
            Map<String, Long> importedBalanceMinor,

            @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            Map<String, Set<String>> readers,

            @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            Map<String, Set<String>> actionRoles) {
        /** 历史收据可能没有附加读者。 @author czhcc-trsdsj\codexsandboxoffline */
        public Receipt {
            readers = readers == null ? Map.of() : Map.copyOf(readers);
            actionRoles = actionRoles == null ? Map.of() : Map.copyOf(actionRoles);
        }
    }

    /** 绑定发起者、生命周期、版本和查询条件的服务端持久记录。 @author czhcc-trsdsj\codexsandboxoffline */
    private record StoredCandidate(
            String provider,
            String actor,
            long version,
            long lifecycle,
            long created,
            String response,
            String digest,
            String taskId,
            Long coverageRevision,
            boolean requested) {}

    /** 一次活跃委派的角色范围快照及租约验证。 @author czhcc-trsdsj\codexsandboxoffline */
    record TaskScope(
            String taskId, String worker, String parent, Set<String> visible, boolean limited, Runnable check) {}

    /** 外部字段来源与已提交的图谱版本。 @author czhcc-trsdsj\codexsandboxoffline */
    public record Provenance(
            String origin,
            String receiptId,
            String providerCode,
            String externalId,
            String sourceVersion,
            String fetchedAt,
            long eventSequence,
            long simulationTime,
            long stateVersion,
            Set<String> fields,

            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String sourceRunId) {}

    /** 查询时预留配额，候选生效前重新校验运行状态。 @author czhcc-trsdsj\codexsandboxoffline */
    public Candidate query(String runId, String operator, String providerCode, Query query) {
        return query(runId, operator, providerCode, query, null);
    }

    Candidate queryForTask(String runId, TaskScope scope, String providerCode, Query query) {
        return query(runId, scope.worker(), providerCode, query, scope);
    }

    private Candidate query(String runId, String actor, String providerCode, Query query, TaskScope task) {
        if (query == null) {
            throw new IllegalArgumentException("Missing external query");
        }
        String candidateId = UUID.randomUUID().toString();
        KnowledgeGraphProvider provider;
        synchronized (runs) {
            var policy = queryPolicy(runId, actor, providerCode, query, task);
            provider = providers.get(providerCode);
            if (provider == null || query.depth() > policy.maxDepth() || query.limit() > policy.maxEntitiesPerCall()) {
                throw new IllegalArgumentException("External query is unavailable or outside its budget");
            }
            tx.executeWithoutResult(status -> {
                if (count("SELECT COUNT(*) FROM simulation_knowledge_candidate WHERE run_id=?", runId)
                                >= policy.maxCalls()
                        || store.eventCount(runId) >= store.scenario(runId).maxEvents() - 2) {
                    throw new IllegalStateException("External query budget exhausted");
                }
                db.update(
                        """
                        INSERT INTO simulation_knowledge_candidate
                        (run_id,candidate_id,provider_code,actor_id,state_version,lifecycle_sequence,created_at,request_json,task_id,coverage_revision)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """,
                        runId,
                        candidateId,
                        providerCode,
                        actor,
                        store.state(runId).version(),
                        lifecycle(runId),
                        System.currentTimeMillis(),
                        Json.write(query),
                        task == null ? null : task.taskId(),
                        store.knowledgeCoverage(runId).revision());
            });
        }
        try {
            var batch = provider.query(query);
            synchronized (runs) {
                return tx.execute(status -> completeQuery(runId, actor, candidateId, batch, query, task));
            }
        } catch (RuntimeException failure) {
            synchronized (runs) {
                if (count("SELECT COUNT(*) FROM simulation_run WHERE id=?", runId) != 0
                        && store.eventCount(runId) < store.scenario(runId).maxEvents()) {
                    store.append(
                            runId,
                            "EXTERNAL_GRAPH_QUERY",
                            store.state(runId).simulationTime(),
                            null,
                            Map.of(
                                    "providerCode",
                                    providerCode,
                                    "candidateId",
                                    candidateId,
                                    "actor",
                                    actor,
                                    "status",
                                    "REJECTED"));
                }
            }
            throw new IllegalStateException("External query failed or the candidate is no longer valid");
        }
    }

    /** 确认已查询的数据批次，使用运行现有的图谱提交和独立的导入审计。 @author czhcc-trsdsj\codexsandboxoffline */
    public Receipt commit(String runId, String operator, String candidateId) {
        synchronized (runs) {
            return tx.execute(status -> {
                var candidate = candidate(runId, operator, candidateId, null, true);
                var policy = allowed(runId, operator, candidate.provider());
                var receipts = db.queryForList(
                        "SELECT receipt_json FROM simulation_knowledge_import WHERE run_id=? AND candidate_id=?",
                        String.class,
                        runId,
                        candidateId);
                if (!receipts.isEmpty()) {
                    return Json.read(receipts.getFirst(), Receipt.class);
                }
                runs.checkKnowledgeBoundary(runId);
                revalidate(runId, candidate, candidate.taskId() == null);
                if (candidate.response() == null
                        || !digest(candidate.response()).equals(candidate.digest())) {
                    throw new IllegalStateException("External query batch is incomplete");
                }
                if (store.eventCount(runId) >= store.scenario(runId).maxEvents()) {
                    throw new IllegalStateException("Run event budget exhausted");
                }
                return commit(runId, operator, candidateId, candidate, policy);
            });
        }
    }

    private Candidate completeQuery(
            String runId, String actor, String id, ExternalSubgraph batch, Query query, TaskScope task) {
        var candidate = candidate(runId, actor, id, task, false);
        var policy = queryPolicy(runId, actor, candidate.provider(), query, task);
        revalidate(runId, candidate, true);
        int limit = query.limit();
        var mapping = policy.sources().get(candidate.provider()).mapping();
        if (batch.entities().size() > limit
                || batch.relations().size() > policy.maxRelationsPerCall()
                || batch.entities().stream().anyMatch(e -> !mapping.entities().containsKey(e.type()))
                || batch.relations().stream().anyMatch(e -> !mapping.relations().containsKey(e.type()))
                || store.eventCount(runId) >= store.scenario(runId).maxEvents()) {
            throw new IllegalArgumentException("External response exceeds its declared query budget or mapping");
        }
        if (task != null) {
            batch = projectTaskBatch(runId, policy.sources().get(candidate.provider()), task, batch);
        }
        var encoded = Json.write(batch);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 2_000_000) {
            throw new IllegalArgumentException("External response exceeds its storage budget");
        }
        db.update(
                "UPDATE simulation_knowledge_candidate SET response_json=?,response_sha256=? WHERE run_id=? AND candidate_id=?",
                encoded,
                digest(encoded),
                runId,
                id);
        var audit = new LinkedHashMap<String, Object>();
        audit.put("actor", actor);
        if (task != null) {
            audit.put("taskId", task.taskId());
        }
        audit.put("candidateId", id);
        audit.put("providerCode", candidate.provider());
        audit.put("fetchedAt", batch.fetchedAt());
        audit.put("sourceVersion", batch.sourceVersion());
        audit.put("coverage", batch.coverage());
        audit.put("inputVersion", candidate.version());
        // This certificate is derived from the authorized projection, not from tool arguments or model text.
        if (task != null
                && query.operation() == KnowledgeGraphProvider.Operation.GET_ENTITY
                && batch.sourceVersion() != null
                && !batch.sourceVersion().isBlank()
                && batch.entities().stream().anyMatch(entity -> query.selector().equals(entity.externalId()))) {
            audit.put("existenceWitness", query.selector());
            audit.put("parentAgentId", task.parent());
            audit.put("scopeIds", new java.util.TreeSet<>(task.visible()));
            audit.put("coverageRevision", candidate.coverageRevision());
        }
        store.append(runId, "EXTERNAL_GRAPH_QUERY", store.state(runId).simulationTime(), null, audit);
        return new Candidate(id, candidate.provider(), candidate.version(), batch);
    }

    private Receipt commit(
            String runId, String operator, String id, StoredCandidate candidate, ExpansionPolicy policy) {
        var source = policy.sources().get(candidate.provider());
        var known = identities(runId, source);
        var batch = Json.read(candidate.response(), ExternalSubgraph.class);
        var current = store.state(runId);
        var preview = SubgraphImport.preview(store.frozenWorld(runId), current, source.mapping(), known, batch);
        var state = preview.state();
        int imported = state.entities().size() - current.entities().size();
        if (imported
                        + count(
                                "SELECT COALESCE(SUM(entity_count),0) FROM simulation_knowledge_import WHERE run_id=?",
                                runId)
                > policy.maxTotalImported()) {
            throw new IllegalStateException("External import budget exhausted");
        }
        store.saveGraph(current, state);
        long sequence = db.queryForObject(
                "SELECT COALESCE(MAX(sequence_no),0)+1 FROM simulation_event WHERE run_id=?", Long.class, runId);
        var receipt = new Receipt(
                id,
                candidate.provider(),
                batch.sourceVersion(),
                batch.fetchedAt(),
                source.mapping().revision(),
                sequence,
                state.simulationTime(),
                state.version(),
                state.version() != current.version(),
                batch.coverage(),
                preview.changedFields(),
                preview.conflicts(),
                preview.importedBalanceMinor(),
                grantedObjects(source, batch, preview, source.readersByExternalId()),
                grantedObjects(source, batch, preview, source.actionRolesByExternalId()));
        store.append(
                runId,
                "WORLD_EXPANDED",
                state.simulationTime(),
                null,
                Map.of(
                        "version",
                        state.version(),
                        "changed",
                        receipt.changed(),
                        "origin",
                        "EXTERNAL_IMPORT",
                        "approvedBy",
                        operator,
                        "receiptId",
                        id,
                        "providerCode",
                        candidate.provider()));
        db.update(
                """
                INSERT INTO simulation_knowledge_import
                (run_id,candidate_id,provider_code,import_order,event_sequence,entity_count,identities_json,receipt_json)
                VALUES (?,?,?,(SELECT COALESCE(MAX(import_order),0)+1 FROM simulation_knowledge_import WHERE run_id=?),?,?,?,?)
                """,
                runId,
                id,
                candidate.provider(),
                runId,
                sequence,
                imported,
                Json.write(preview.identities()),
                Json.write(receipt));
        db.update(
                "UPDATE simulation_knowledge_candidate SET response_json=NULL WHERE run_id=? AND candidate_id=?",
                runId,
                id);
        return receipt;
    }

    private Map<String, Set<String>> grantedObjects(
            ExpansionPolicy.Source source,
            ExternalSubgraph batch,
            SubgraphImport.Preview preview,
            Map<String, Set<String>> approvedRoles) {
        var liveIds =
                preview.state().entities().stream().map(entity -> entity.id()).collect(Collectors.toSet());
        var readers = new java.util.TreeMap<String, Set<String>>();
        for (var entity : batch.entities()) {
            if (source.initialIdentities().entities().containsKey(entity.externalId())) {
                // 导入不能借用一个原有本地对象，为它发放额外读取权限。
                continue;
            }
            var internalId = preview.identities().entities().get(entity.externalId());
            if (internalId == null || !liveIds.contains(internalId)) {
                continue;
            }
            for (var reader : approvedRoles.getOrDefault(entity.externalId(), Set.of())) {
                readers.computeIfAbsent(reader, key -> new java.util.TreeSet<>())
                        .add(internalId);
            }
        }
        return readers;
    }

    private Identities identities(String runId, ExpansionPolicy.Source source) {
        var previous =
                db.queryForList("""
                SELECT identities_json FROM simulation_knowledge_import
                WHERE run_id=? AND provider_code=? ORDER BY import_order DESC LIMIT 1
                """, String.class, runId, source.mapping().providerCode());
        return previous.isEmpty() ? source.initialIdentities() : Json.read(previous.getFirst(), Identities.class);
    }

    private ExternalSubgraph projectTaskBatch(
            String runId, ExpansionPolicy.Source source, TaskScope task, ExternalSubgraph batch) {
        var known = identities(runId, source).entities();
        var visible = new HashSet<String>();
        known.forEach((external, local) -> {
            if (task.visible().contains(local)) {
                visible.add(external);
            }
        });
        var entities = batch.entities().stream()
                .filter(entity -> {
                    var id = entity.externalId();
                    if (known.containsKey(id)) {
                        return visible.contains(id);
                    }
                    var readers = source.readersByExternalId().getOrDefault(id, Set.of());
                    return !task.limited() && readers.contains(task.worker()) && readers.contains(task.parent());
                })
                .toList();
        entities.forEach(entity -> visible.add(entity.externalId()));
        var relations = batch.relations().stream()
                .filter(edge -> visible.contains(edge.sourceId()) && visible.contains(edge.targetId()))
                .toList();
        boolean filtered = entities.size() != batch.entities().size()
                || relations.size() != batch.relations().size();
        var scope = batch.coverage();
        return new ExternalSubgraph(
                batch.sourceVersion(),
                batch.fetchedAt(),
                new ExternalSubgraph.Coverage(
                        "AUTHORIZED_EXTERNAL_TASK",
                        filtered ? "PARTIAL" : scope.completeness(),
                        filtered || scope.truncated(),
                        scope.hasMore()),
                entities,
                relations);
    }

    void requestForTask(String runId, TaskScope task, String id) {
        synchronized (runs) {
            tx.executeWithoutResult(status -> {
                task.check().run();
                var candidate = candidate(runId, task.worker(), id, task, false);
                revalidate(runId, candidate, true);
                if (candidate.requested()) {
                    return;
                }
                if (candidate.response() == null
                        || !digest(candidate.response()).equals(candidate.digest())) {
                    throw new IllegalStateException("External query batch is incomplete");
                }
                var batch = Json.read(candidate.response(), ExternalSubgraph.class);
                if (batch.entities().isEmpty() && batch.relations().isEmpty()) {
                    throw new IllegalArgumentException("No authorized external data to import");
                }
                if (store.eventCount(runId) >= store.scenario(runId).maxEvents()) {
                    throw new IllegalStateException("Run event budget exhausted");
                }
                db.update(
                        "UPDATE simulation_knowledge_candidate SET import_requested=1 WHERE run_id=? AND candidate_id=?",
                        runId,
                        id);
                store.append(
                        runId,
                        "EXTERNAL_IMPORT_REQUESTED",
                        store.state(runId).simulationTime(),
                        task.worker(),
                        Map.of("candidateId", id, "providerCode", candidate.provider(), "taskId", task.taskId()));
            });
        }
    }

    private ExpansionPolicy queryPolicy(String runId, String actor, String provider, Query query, TaskScope task) {
        if (task == null) {
            runs.checkKnowledgeBoundary(runId);
            return allowed(runId, actor, provider);
        }
        task.check().run();
        var policy = ExpansionPolicy.from(store.scenario(runId).expansionPolicy());
        var source = policy == null ? null : policy.sources().get(provider);
        if (source == null
                || !source.workerQueries().getOrDefault(actor, Set.of()).contains(query)) {
            throw new IllegalArgumentException("External query is outside this task's frozen policy");
        }
        return policy;
    }

    /** 在确认前读取可用的精确服务端候选。 @author czhcc-trsdsj\codexsandboxoffline */
    public Candidate inspect(String runId, String operator, String id) {
        synchronized (runs) {
            var candidate = candidate(runId, operator, id, null, true);
            allowed(runId, operator, candidate.provider());
            revalidate(runId, candidate, candidate.taskId() == null);
            if (candidate.response() == null || !digest(candidate.response()).equals(candidate.digest())) {
                throw new IllegalStateException("External query batch is incomplete or already imported");
            }
            return new Candidate(
                    id,
                    candidate.provider(),
                    candidate.version(),
                    Json.read(candidate.response(), ExternalSubgraph.class));
        }
    }

    /** 操作人拒绝一个申请并释放审批等待。 @author czhcc-trsdsj\codexsandboxoffline */
    public void reject(String runId, String operator, String id) {
        synchronized (runs) {
            tx.executeWithoutResult(status -> {
                var record = candidate(runId, operator, id, null, true);
                allowed(runId, operator, record.provider());
                runs.checkKnowledgeBoundary(runId);
                if (record.taskId() == null || !record.requested() || record.response() == null) {
                    throw new IllegalArgumentException("No pending external import to reject");
                }
                db.update(
                        "UPDATE simulation_knowledge_candidate SET response_json=NULL WHERE run_id=? AND candidate_id=?",
                        runId,
                        id);
                store.append(
                        runId,
                        "EXTERNAL_IMPORT_REJECTED",
                        store.state(runId).simulationTime(),
                        null,
                        Map.of("candidateId", id, "approvedBy", operator));
            });
        }
    }

    /** 撤销导入生成的读取和本地写授权，保持图谱与时钟不变。 @author czhcc-trsdsj\codexsandboxoffline */
    public long revoke(String runId, String operator, String role, String objectId) {
        synchronized (runs) {
            return tx.execute(status -> {
                operatorPolicy(runId, operator);
                runs.checkKnowledgeBoundary(runId);
                var previous = db.queryForList("""
                        SELECT event_sequence FROM simulation_knowledge_revocation
                        WHERE run_id=? AND role_id=? AND object_id=?
                        """, Long.class, runId, role, objectId);
                if (!previous.isEmpty()) {
                    return previous.getFirst();
                }
                boolean exists = importRecords(runId).stream()
                        .anyMatch(row -> row.receipt()
                                .readers()
                                .getOrDefault(role, Set.of())
                                .contains(objectId));
                if (!exists || store.eventCount(runId) >= store.scenario(runId).maxEvents()) {
                    throw new IllegalArgumentException("No revocable external object grant or event budget");
                }
                store.append(
                        runId,
                        "EXTERNAL_GRANT_REVOKED",
                        store.state(runId).simulationTime(),
                        null,
                        Map.of("roleId", role, "objectId", objectId, "approvedBy", operator));
                long sequence = store.knowledgeCoverage(runId).revision();
                db.update(
                        "INSERT INTO simulation_knowledge_revocation VALUES (?,?,?,?)",
                        runId,
                        role,
                        objectId,
                        sequence);
                return sequence;
            });
        }
    }

    /** 从已持久字段来源读取外部事实，不再查询数据源。 @author czhcc-trsdsj\codexsandboxoffline */
    public List<Provenance> provenance(String runId, String operator, String objectId) {
        synchronized (runs) {
            operatorPolicy(runId, operator);
            return fieldProvenance(runId, objectId);
        }
    }

    private List<Provenance> fieldProvenance(String runId, String objectId) {
        if (objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("A provenance object is required");
        }
        var history = store.fieldHistory(runId);
        var sourceRuns = new LinkedHashMap<String, String>();
        for (var event : history) {
            if ("WORLD_EXPANDED".equals(event.eventType())
                    && event.payload().get("receiptId") instanceof String receipt) {
                sourceRuns.putIfAbsent(receipt, event.runId());
            }
        }
        var imports = importRecords(runId);
        var rows = new java.util.ArrayList<Provenance>();
        for (var entry : imports) {
            var receipt = entry.receipt();
            var fields = receipt.changedFields().get(objectId);
            if (fields == null) {
                continue;
            }
            var ids = objectId.startsWith("relation:")
                    ? entry.identity().relations()
                    : entry.identity().entities();
            var target = objectId.startsWith("relation:") ? objectId.substring("relation:".length()) : objectId;
            ids.entrySet().stream()
                    .filter(id -> id.getValue().equals(target))
                    .findFirst()
                    .ifPresent(id -> rows.add(new Provenance(
                            "EXTERNAL",
                            receipt.receiptId(),
                            receipt.providerCode(),
                            id.getKey(),
                            receipt.sourceVersion(),
                            receipt.fetchedAt(),
                            receipt.eventSequence(),
                            receipt.simulationTime(),
                            receipt.stateVersion(),
                            fields,
                            sourceRuns.get(receipt.receiptId()))));
        }
        var relation = objectId.startsWith("relation:");
        var target = relation ? objectId.substring("relation:".length()) : objectId;
        for (var event : history) {
            if (!"WORLD_STATE_CHANGED".equals(event.eventType())
                    || !(event.payload().get("stateDiff") instanceof List<?> changes)
                    || !(event.payload().get("version") instanceof Number version)
                    || !(event.payload().get("actionRunId") instanceof String actionId)) {
                continue;
            }
            for (var raw : changes) {
                if (!(raw instanceof Map<?, ?> change)
                        || !target.equals(change.get("id"))
                        || !(relation ? "RELATION" : "ENTITY").equals(change.get("kind"))
                        || !(change.get("fields") instanceof List<?> fields)) {
                    continue;
                }
                var keys = fields.stream()
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .map(field -> field.get("code"))
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .collect(Collectors.toUnmodifiableSet());
                rows.add(new Provenance(
                        "SIMULATED",
                        actionId,
                        null,
                        null,
                        null,
                        null,
                        event.sequence(),
                        event.simulationTime(),
                        version.longValue(),
                        keys,
                        event.runId()));
            }
        }
        return List.copyOf(rows);
    }

    /** 持久的字段来源及稳定身份。 @author czhcc-trsdsj\codexsandboxoffline */
    private record ImportRecord(Receipt receipt, Identities identity) {}

    private List<ImportRecord> importRecords(String runId) {
        return db.query(
                """
                SELECT receipt_json,identities_json FROM simulation_knowledge_import WHERE run_id=? ORDER BY import_order
                """,
                (row, index) -> new ImportRecord(
                        Json.read(row.getString(1), Receipt.class), Json.read(row.getString(2), Identities.class)),
                runId);
    }

    private ExpansionPolicy operatorPolicy(String runId, String operator) {
        var policy = ExpansionPolicy.from(store.scenario(runId).expansionPolicy());
        if (policy == null || !policy.operators().contains(operator)) {
            throw new IllegalArgumentException("External expansion is outside this operator's policy");
        }
        return policy;
    }

    private ExpansionPolicy allowed(String runId, String operator, String provider) {
        var policy = operatorPolicy(runId, operator);
        if (!policy.sources().containsKey(provider)) {
            throw new IllegalArgumentException("External source is outside this run's expansion policy");
        }
        return policy;
    }

    private StoredCandidate candidate(String runId, String actor, String id, TaskScope task, boolean operatorApproval) {
        return db
                .query(
                        """
                SELECT provider_code,actor_id,state_version,lifecycle_sequence,created_at,response_json,
                    response_sha256,task_id,coverage_revision,import_requested
                FROM simulation_knowledge_candidate WHERE run_id=? AND candidate_id=?
                AND ((actor_id=? AND task_id IS ?) OR (?=1 AND task_id IS NOT NULL AND import_requested=1))
                """,
                        (row, index) -> new StoredCandidate(
                                row.getString(1),
                                row.getString(2),
                                row.getLong(3),
                                row.getLong(4),
                                row.getLong(5),
                                row.getString(6),
                                row.getString(7),
                                row.getString(8),
                                row.getObject(9) instanceof Number revision ? revision.longValue() : null,
                                row.getBoolean(10)),
                        runId,
                        id,
                        actor,
                        task == null ? null : task.taskId(),
                        operatorApproval ? 1 : 0)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown external query batch"));
    }

    private void revalidate(String id, StoredCandidate candidate, boolean verifyLifecycle) {
        if (store.state(id).version() != candidate.version()
                || verifyLifecycle && lifecycle(id) != candidate.lifecycle()
                || candidate.coverageRevision() != null
                        && store.knowledgeCoverage(id).revision() != candidate.coverageRevision()
                || System.currentTimeMillis() - candidate.created()
                        >= RunStateRepository.KNOWLEDGE_CANDIDATE_TTL_MILLIS) {
            throw new IllegalStateException("External query batch is stale");
        }
    }

    private long lifecycle(String id) {
        return db.queryForObject("""
                SELECT COALESCE(MAX(sequence_no),0) FROM simulation_event WHERE run_id=?
                AND event_type IN ('RUN_PAUSED','RUN_RESUMED','RUN_STOPPED','RUN_FAILED',
                                   'REASONING_ROUND_STARTED','AGENT_STARTED')
                """, Long.class, id);
    }

    private int count(String query, String id) {
        return db.queryForObject(query, Integer.class, id);
    }

    private static String digest(String payload) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}

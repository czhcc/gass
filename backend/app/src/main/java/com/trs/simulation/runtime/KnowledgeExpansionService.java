package com.trs.simulation.runtime;

import com.trs.simulation.common.Json;
import com.trs.simulation.knowledge.ExpansionPolicy;
import com.trs.simulation.knowledge.ExternalSubgraph;
import com.trs.simulation.knowledge.KnowledgeGraphProvider;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Query;
import com.trs.simulation.knowledge.SubgraphImport;
import com.trs.simulation.knowledge.SubgraphImport.Identities;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
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
    private static final long CANDIDATE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    private final RunService runs;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RunStateRepository store;
    private final Map<String, KnowledgeGraphProvider> providers;

    /** 装配已在服务器注册的只读 Provider。 @author czhcc-trsdsj\codexsandboxoffline */
    public KnowledgeExpansionService(
            RunService runs, JdbcTemplate db, TransactionTemplate tx, List<KnowledgeGraphProvider> providers) {
        this.runs = runs;
        this.db = db;
        this.tx = tx;
        this.store = new RunStateRepository(db);
        this.providers = providers.stream()
                .collect(Collectors.toUnmodifiableMap(KnowledgeGraphProvider::providerCode, provider -> provider));
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
            Map<String, Long> importedBalanceMinor) {}

    /** 绑定发起者、生命周期、版本和查询条件的服务端持久记录。 @author czhcc-trsdsj\codexsandboxoffline */
    private record StoredCandidate(
            String provider,
            String actor,
            long version,
            long lifecycle,
            long created,
            String response,
            String digest) {}

    /** 查询时预留配额，候选生效前重新校验运行状态。 @author czhcc-trsdsj\codexsandboxoffline */
    public Candidate query(String runId, String operator, String providerCode, Query query) {
        if (query == null) {
            throw new IllegalArgumentException("Missing external query");
        }
        String candidateId = UUID.randomUUID().toString();
        KnowledgeGraphProvider provider;
        synchronized (runs) {
            var policy = allowed(runId, operator, providerCode);
            runs.checkKnowledgeBoundary(runId);
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
                        (run_id,candidate_id,provider_code,actor_id,state_version,lifecycle_sequence,created_at,request_json)
                        VALUES (?,?,?,?,?,?,?,?)
                        """,
                        runId,
                        candidateId,
                        providerCode,
                        operator,
                        store.state(runId).version(),
                        lifecycle(runId),
                        System.currentTimeMillis(),
                        Json.write(query));
            });
        }
        try {
            var batch = provider.query(query);
            synchronized (runs) {
                return tx.execute(status -> completeQuery(runId, operator, candidateId, batch, query.limit()));
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
                                    operator,
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
                var candidate = candidate(runId, operator, candidateId);
                var policy = allowed(runId, operator, candidate.provider());
                var receipts = db.queryForList(
                        "SELECT receipt_json FROM simulation_knowledge_import WHERE run_id=? AND candidate_id=?",
                        String.class,
                        runId,
                        candidateId);
                if (!receipts.isEmpty()) {
                    return Json.read(receipts.getFirst(), Receipt.class);
                }
                revalidate(runId, candidate);
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

    private Candidate completeQuery(String runId, String operator, String id, ExternalSubgraph batch, int limit) {
        var candidate = candidate(runId, operator, id);
        var policy = allowed(runId, operator, candidate.provider());
        revalidate(runId, candidate);
        var mapping = policy.sources().get(candidate.provider()).mapping();
        if (batch.entities().size() > limit
                || batch.relations().size() > policy.maxRelationsPerCall()
                || batch.entities().stream().anyMatch(e -> !mapping.entities().containsKey(e.type()))
                || batch.relations().stream().anyMatch(e -> !mapping.relations().containsKey(e.type()))
                || store.eventCount(runId) >= store.scenario(runId).maxEvents()) {
            throw new IllegalArgumentException("External response exceeds its declared query budget or mapping");
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
        audit.put("actor", operator);
        audit.put("candidateId", id);
        audit.put("providerCode", candidate.provider());
        audit.put("fetchedAt", batch.fetchedAt());
        audit.put("sourceVersion", batch.sourceVersion());
        audit.put("coverage", batch.coverage());
        audit.put("inputVersion", candidate.version());
        store.append(runId, "EXTERNAL_GRAPH_QUERY", store.state(runId).simulationTime(), null, audit);
        return new Candidate(id, candidate.provider(), candidate.version(), batch);
    }

    private Receipt commit(
            String runId, String operator, String id, StoredCandidate candidate, ExpansionPolicy policy) {
        var source = policy.sources().get(candidate.provider());
        var previous = db.queryForList("""
                SELECT identities_json FROM simulation_knowledge_import
                WHERE run_id=? AND provider_code=? ORDER BY import_order DESC LIMIT 1
                """, String.class, runId, candidate.provider());
        var known = previous.isEmpty() ? source.initialIdentities() : Json.read(previous.getFirst(), Identities.class);
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
                preview.importedBalanceMinor());
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

    private ExpansionPolicy allowed(String id, String operator, String provider) {
        var policy = store.scenario(id).expansionPolicy();
        if (policy == null
                || !policy.operators().contains(operator)
                || !policy.sources().containsKey(provider)) {
            throw new IllegalArgumentException("External source is outside this run's expansion policy");
        }
        return policy;
    }

    private StoredCandidate candidate(String runId, String operator, String id) {
        return db
                .query(
                        """
                SELECT provider_code,actor_id,state_version,lifecycle_sequence,created_at,response_json,response_sha256
                FROM simulation_knowledge_candidate WHERE run_id=? AND candidate_id=? AND actor_id=?
                """,
                        (row, number) -> new StoredCandidate(
                                row.getString(1),
                                row.getString(2),
                                row.getLong(3),
                                row.getLong(4),
                                row.getLong(5),
                                row.getString(6),
                                row.getString(7)),
                        runId,
                        id,
                        operator)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown external query batch"));
    }

    private void revalidate(String id, StoredCandidate candidate) {
        runs.checkKnowledgeBoundary(id);
        if (store.state(id).version() != candidate.version()
                || lifecycle(id) != candidate.lifecycle()
                || System.currentTimeMillis() - candidate.created() >= CANDIDATE_TTL_MILLIS) {
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

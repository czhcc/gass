package com.trs.simulation;

import com.trs.simulation.SimulationTypes.ActionDefinition;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Ontology;
import com.trs.simulation.SimulationTypes.Outcome;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.TypeDefinition;
import com.trs.simulation.SimulationTypes.World;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Maintains immutable published contracts and explicit, version-checked world bindings. @author czhcc-trsdsj\czhcc */
@Service
public class ActionLibraryService {
    /** Safe persisted metadata; model credentials and graph data are never stored here. @author czhcc-trsdsj\czhcc */
    public record Entry(
            String id,
            String domainCode,
            String ownerType,
            int revision,
            String status,
            String source,
            String modelId,
            ActionDefinition definition,
            int version) {}
    /** Draft edit or validation input; version provides optimistic concurrency. @author czhcc-trsdsj\czhcc */
    public record Input(String domainCode, String ownerType, ActionDefinition definition, int version) {}
    /** Generation request uses a saved model and saves a validated draft only. @author czhcc-trsdsj\czhcc */
    public record Generation(String domainCode, String ownerType, String modelId, String description) {}
    /** Dry-run input uses an isolated current world snapshot without changing stored state. @author czhcc-trsdsj\czhcc */
    public record Preview(
            String domainCode,
            String ownerType,
            ActionDefinition definition,
            String worldId,
            String targetId,
            Map<String, Object> parameters) {}

    private final JdbcTemplate db;
    private final WorldService worlds;
    private final ModelService models;
    private final AgentScopeDecisionProvider provider;
    private boolean seeded;

    /** Connects library persistence, world maintenance and the configured model adapter. @author czhcc-trsdsj\czhcc */
    public ActionLibraryService(
            JdbcTemplate db, WorldService worlds, ModelService models, AgentScopeDecisionProvider provider) {
        this.db = db;
        this.worlds = worlds;
        this.models = models;
        this.provider = provider;
    }

    /** Lists built-in templates alongside manually authored and generated revisions. @author czhcc-trsdsj\czhcc */
    public synchronized List<Entry> list() {
        seed();
        return db.query(
                "SELECT entry_json FROM action_library ORDER BY domain_code,owner_type,action_type,revision DESC",
                (r, n) -> Json.read(r.getString(1), Entry.class));
    }

    /** Loads one revision without resolving it through a mutable latest-version alias. @author czhcc-trsdsj\czhcc */
    public synchronized Entry get(String id) {
        seed();
        return db
                .query(
                        "SELECT entry_json FROM action_library WHERE id=?",
                        (r, n) -> Json.read(r.getString(1), Entry.class),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Action revision not found"));
    }

    /** Saves a manual draft as a new revision; it has no effect on worlds or runs. @author czhcc-trsdsj\czhcc */
    public synchronized Entry create(Input input) {
        validate(input);
        return create(input, "MANUAL", null);
    }

    /** Edits a draft only, preserving immutable published revisions. @author czhcc-trsdsj\czhcc */
    public synchronized Entry update(String id, Input input) {
        var old = editable(id, input.version());
        validate(input);
        require(
                old.domainCode().equals(input.domainCode())
                        && old.ownerType().equals(input.ownerType())
                        && old.definition()
                                .actionType()
                                .equals(input.definition().actionType()),
                "Draft identity cannot be changed; create another draft");
        var next = new Entry(
                id,
                old.domainCode(),
                old.ownerType(),
                old.revision(),
                "DRAFT",
                "MANUAL",
                null,
                input.definition(),
                old.version() + 1);
        save(next);
        return next;
    }

    /** Checks the schema, supported primitives and CEL compilation before saving or publishing. @author czhcc-trsdsj\czhcc */
    public void validate(Input input) {
        require(input != null, "Action input required");
        owner(input.domainCode(), input.ownerType());
        ActionPrograms.validate(input.definition());
        require(
                input.definition().program().operations().stream().noneMatch(o -> o.op().equals("TRANSFER_MONEY"))
                        || (input.domainCode().equals("bank")
                                && input.ownerType().equals("BANK_ACCOUNT")),
                "TRANSFER_MONEY belongs to BANK_ACCOUNT only");
    }

    /** Publishes a validated immutable revision; world binding remains an explicit separate action. @author czhcc-trsdsj\czhcc */
    public synchronized Entry publish(String id, int version) {
        var e = editable(id, version);
        validate(new Input(e.domainCode(), e.ownerType(), e.definition(), version));
        var next = new Entry(
                id,
                e.domainCode(),
                e.ownerType(),
                e.revision(),
                "PUBLISHED",
                e.source(),
                e.modelId(),
                e.definition(),
                e.version() + 1);
        save(next);
        return next;
    }

    /** Deletes an unpublished draft after checking that the editor is current. @author czhcc-trsdsj\czhcc */
    public synchronized void delete(String id, int version) {
        editable(id, version);
        db.update("DELETE FROM action_library WHERE id=?", id);
    }

    /** Generates and validates a draft; neither the generator nor this method executes its effects. @author czhcc-trsdsj\czhcc */
    public Entry generate(Generation request) {
        require(
                request != null
                        && request.description() != null
                        && !request.description().isBlank()
                        && request.description().length() <= 4000,
                "Describe the Action in at most 4000 characters");
        owner(request.domainCode(), request.ownerType());
        var config = models.get(request.modelId());
        var definition = provider.generateAction(
                request.modelId(), request.domainCode(), request.ownerType(), request.description());
        var input = new Input(request.domainCode(), request.ownerType(), definition, 0);
        validate(input);
        synchronized (this) {
            return create(input, config.baseUrl().startsWith("mock:") ? "MOCK" : "LLM", config.id());
        }
    }

    /** Evaluates a candidate against copied data using the same validation and primitives as a real run. @author czhcc-trsdsj\czhcc */
    public Outcome preview(Preview input) {
        require(input != null, "Preview input required");
        validate(new Input(input.domainCode(), input.ownerType(), input.definition(), 0));
        var w = worlds.get(input.worldId());
        require(
                w.domainCode().equals(input.domainCode())
                        && w.entities().stream()
                                .anyMatch(e -> e.id().equals(input.targetId())
                                        && e.type().equals(input.ownerType())),
                "Choose an owner object in a matching world");
        var bound = withDefinition(w, input.ownerType(), input.definition());
        var agent = new AgentDefinition(
                "preview",
                "Preview",
                "Dry run",
                "",
                List.of(input.definition().actionType()),
                w.entities().stream().map(Entity::id).toList(),
                1);
        return new DeclarativeActionEngine()
                .execute(
                        bound,
                        agent,
                        new Decision(
                                input.definition().actionType(),
                                input.targetId(),
                                input.parameters(),
                                "Isolated Action preview",
                                "ENTITY"),
                        new State("preview", 0, 0, w.entities(), w.relations()));
    }

    /** Copies a published revision into a versioned world template, leaving frozen runs unchanged. @author czhcc-trsdsj\czhcc */
    public synchronized World bind(String id, String worldId, int expectedVersion) {
        var e = get(id);
        if (!e.status().equals("PUBLISHED"))
            throw new IllegalStateException("Publish the Action revision before binding it");
        var w = worlds.get(worldId);
        require(
                w.domainCode().equals(e.domainCode()) && w.entityTypes().contains(e.ownerType()),
                "Action owner type and world must match");
        if (w.version() != expectedVersion)
            throw new WorldService.Conflict("WORLD_STALE", "World changed; reload before binding");
        return worlds.update(w.id(), withDefinition(w, e.ownerType(), e.definition()));
    }

    /** Removes a binding from the future world template without deleting historical definitions. @author czhcc-trsdsj\czhcc */
    public synchronized World unbind(String worldId, String ownerType, String actionType, int version) {
        var w = worlds.get(worldId);
        var ontology = new Ontology(
                3,
                w.ontology().types().stream()
                        .map(t -> t.type().equals(ownerType)
                                ? new TypeDefinition(
                                        "ENTITY",
                                        t.type(),
                                        t.actions().stream()
                                                .filter(a -> !a.actionType().equals(actionType))
                                                .toList())
                                : t)
                        .toList());
        return worlds.update(
                worldId,
                new World(
                        w.id(),
                        w.name(),
                        w.domainCode(),
                        w.entityTypes(),
                        w.relationTypes(),
                        w.entities(),
                        w.relations(),
                        version,
                        ontology));
    }

    private Entry create(Input input, String source, String modelId) {
        seed();
        Integer revision = db.queryForObject(
                "SELECT COALESCE(MAX(revision),0)+1 FROM action_library WHERE domain_code=? AND owner_type=? AND action_type=?",
                Integer.class,
                input.domainCode(),
                input.ownerType(),
                input.definition().actionType());
        var e = new Entry(
                UUID.randomUUID().toString(),
                input.domainCode(),
                input.ownerType(),
                revision,
                "DRAFT",
                source,
                modelId,
                input.definition(),
                1);
        db.update(
                "INSERT INTO action_library VALUES (?,?,?,?,?,?)",
                e.id(),
                e.domainCode(),
                e.ownerType(),
                e.definition().actionType(),
                e.revision(),
                Json.write(e));
        return e;
    }

    private Entry editable(String id, int version) {
        var e = get(id);
        if (!e.status().equals("DRAFT"))
            throw new IllegalStateException("Published revisions are immutable; create a new draft revision");
        if (e.version() != version) throw new IllegalStateException("Action draft changed; reload before editing");
        return e;
    }

    private void save(Entry e) {
        db.update("UPDATE action_library SET entry_json=? WHERE id=?", Json.write(e), e.id());
    }

    private void seed() {
        if (seeded) return;
        for (var domain : List.of("information", "bank"))
            for (var type : ActionPrograms.ontology(domain).types())
                for (var a : type.actions()) {
                    var e = new Entry(
                            "builtin-" + domain + "-" + type.type() + "-" + a.actionType(),
                            domain,
                            type.type(),
                            1,
                            "PUBLISHED",
                            "BUILTIN",
                            null,
                            a,
                            1);
                    db.update(
                            "INSERT OR IGNORE INTO action_library VALUES (?,?,?,?,?,?)",
                            e.id(),
                            domain,
                            type.type(),
                            a.actionType(),
                            1,
                            Json.write(e));
                }
        seeded = true;
    }

    private void owner(String domain, String type) {
        require(
                domain != null && type != null && type.matches("[A-Za-z][A-Za-z0-9_]{0,63}"),
                "Action domain and object type required");
        require(
                ActionPrograms.ontology(domain).types().stream()
                                .anyMatch(t -> t.type().equals(type))
                        || worlds.list().stream()
                                .anyMatch(w -> w.domainCode().equals(domain)
                                        && w.entityTypes().contains(type)),
                "Unknown Action owner type");
    }

    private World withDefinition(World w, String type, ActionDefinition definition) {
        var types = new ArrayList<TypeDefinition>();
        boolean found = false;
        for (var t : w.ontology().types()) {
            if (!t.type().equals(type)) {
                types.add(t);
                continue;
            }
            found = true;
            var actions = new ArrayList<>(t.actions().stream()
                    .filter(a -> !a.actionType().equals(definition.actionType()))
                    .toList());
            actions.add(definition);
            types.add(new TypeDefinition("ENTITY", type, List.copyOf(actions)));
        }
        if (!found) types.add(new TypeDefinition("ENTITY", type, List.of(definition)));
        return new World(
                w.id(),
                w.name(),
                w.domainCode(),
                w.entityTypes(),
                w.relationTypes(),
                w.entities(),
                w.relations(),
                w.version(),
                new Ontology(3, List.copyOf(types)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

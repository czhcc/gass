package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.util.*;
import org.jgrapht.alg.scoring.BetweennessCentrality;
import org.jgrapht.alg.shortestpath.BFSShortestPath;
import org.jgrapht.graph.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional world template and bounded property-graph queries. @author agent */
@Service
public class WorldService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Map<String, DomainEnvironment> domains = new HashMap<>();

  /** Injects persistence and transaction boundaries. @author agent */
  public WorldService(JdbcTemplate db, TransactionTemplate tx, List<DomainEnvironment> plugins) {
    this.db = db;
    this.tx = tx;
    plugins.forEach(p -> domains.put(p.domainCode(), p));
  }

  /** Lists stored templates. @author agent */
  public List<World> list() {
    return db.query(
        "SELECT definition_json FROM world_definition ORDER BY id",
        (r, n) -> Json.read(r.getString(1), World.class));
  }

  /** Reads a world or fails with a domain error. @author agent */
  public World get(String id) {
    return db
        .query(
            "SELECT definition_json FROM world_definition WHERE id=?",
            (r, n) -> Json.read(r.getString(1), World.class),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("World not found"));
  }

  /**
   * Validates graph structure then atomically stores the template and graph index. @author agent
   */
  public synchronized World create(World input) {
    require(
        input != null && input.name() != null && !input.name().isBlank(), "World name required");
    require(
        input.entities() != null
            && input.entities().size() <= 200
            && input.relations() != null
            && input.relations().size() <= 1000,
        "World size limit exceeded");
    require(
        input.entityTypes() != null && input.relationTypes() != null, "Type definitions required");
    require(domains.containsKey(input.domainCode()), "Unsupported domain");
    var ids = new HashSet<String>();
    var edges = new HashSet<String>();
    for (var e : input.entities()) {
      require(
          e.id() != null && e.id().matches("[A-Za-z0-9_-]{1,100}") && ids.add(e.id()),
          "Invalid or duplicate entity id");
      require(
          input.entityTypes().contains(e.type())
              && e.properties() != null
              && e.name() != null
              && e.name().length() <= 200,
          "Invalid entity type or properties");
    }
    for (var e : input.relations()) {
      require(
          e.id() != null
              && edges.add(e.id())
              && ids.contains(e.sourceId())
              && ids.contains(e.targetId()),
          "Invalid edge endpoints or duplicate id");
      require(
          input.relationTypes().contains(e.relationType())
              && List.of("FACT", "INFERRED", "HYPOTHESIS", "SIMULATED").contains(e.evidence()),
          "Invalid edge type or evidence");
    }
    domains.get(input.domainCode()).validate(input);
    var w =
        new World(
            UUID.randomUUID().toString(),
            input.name(),
            input.domainCode(),
            input.entityTypes(),
            input.relationTypes(),
            input.entities(),
            input.relations(),
            1);
    require(Json.write(w).length() < 1_000_000, "World too large");
    tx.executeWithoutResult(
        status -> {
          db.update("INSERT INTO world_definition VALUES (?,?)", w.id(), Json.write(w));
          for (var e : w.entities())
            db.update(
                "INSERT INTO world_entity VALUES (?,?,?,?)",
                w.id(),
                e.id(),
                e.type(),
                Json.write(e.properties()));
          for (var e : w.relations())
            db.update(
                "INSERT INTO world_relation VALUES (?,?,?,?,?)",
                w.id(),
                e.id(),
                e.sourceId(),
                e.targetId(),
                e.relationType());
        });
    return w;
  }

  /** Performs a cycle-safe directed expansion using a recursive SQLite CTE. @author agent */
  public List<String> expand(String world, String entity, int depth, String type) {
    get(world);
    require(depth >= 0 && depth <= 6, "Depth must be between 0 and 6");
    return db.query(
        "WITH RECURSIVE walk(id,depth,path) AS (SELECT id,0,','||id||',' FROM world_entity WHERE"
            + " world_id=? AND id=? UNION ALL SELECT r.target_id,w.depth+1,w.path||r.target_id||','"
            + " FROM walk w JOIN world_relation r ON r.world_id=? AND r.source_id=w.id WHERE"
            + " w.depth<? AND INSTR(w.path,','||r.target_id||',')=0 AND (? IS NULL OR"
            + " r.relation_type=?) LIMIT 2000) SELECT DISTINCT id FROM walk ORDER BY id LIMIT 200",
        (r, n) -> r.getString(1),
        world,
        entity,
        world,
        depth,
        type,
        type);
  }

  /** Finds the shortest directed path through enabled relationships. @author agent */
  public List<String> path(String world, String from, String to) {
    var g = graph(get(world));
    if (!g.containsVertex(from) || !g.containsVertex(to)) return List.of();
    var p = new BFSShortestPath<>(g).getPath(from, to);
    return p == null ? List.of() : p.getVertexList();
  }

  /** Returns explainable local betweenness scores keyed by entity id. @author agent */
  public Map<String, Double> centrality(String world) {
    return new TreeMap<>(new BetweennessCentrality<>(graph(get(world))).getScores());
  }

  private DefaultDirectedGraph<String, DefaultEdge> graph(World w) {
    var g = new DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge.class);
    w.entities().forEach(e -> g.addVertex(e.id()));
    w.relations().stream()
        .filter(Relation::enabled)
        .forEach(e -> g.addEdge(e.sourceId(), e.targetId()));
    return g;
  }

  /** Lists scenario definitions. @author agent */
  public List<Scenario> scenarios() {
    return db.query(
        "SELECT definition_json FROM scenario ORDER BY id",
        (r, n) -> Json.read(r.getString(1), Scenario.class));
  }

  /** Gets an editable scenario. @author agent */
  public Scenario scenario(String id) {
    return db
        .query(
            "SELECT definition_json FROM scenario WHERE id=?",
            (r, n) -> Json.read(r.getString(1), Scenario.class),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("Scenario not found"));
  }

  /** Validates scenario budgets, visibility and registered action permissions. @author agent */
  public synchronized Scenario scenario(Scenario s) {
    var w = get(s.worldId());
    require(s.name() != null && !s.name().isBlank(), "Scenario name required");
    require(
        s.agents() != null && !s.agents().isEmpty() && s.agents().size() <= 20,
        "Requires 1 to 20 agents");
    require(
        s.maxSteps() > 0 && s.maxSteps() <= 20 && s.maxEvents() >= 20 && s.maxEvents() <= 2000,
        "Invalid runtime budgets");
    Set<String> agents = new HashSet<>();
    var ids = w.entities().stream().map(Entity::id).toList();
    for (var a : s.agents()) {
      require(
          a.id() != null
              && agents.add(a.id())
              && a.goal() != null
              && !a.goal().isBlank()
              && a.goal().length() <= 4000
              && a.modelId() != null,
          "Invalid agent");
      require(
          a.maxDecisions() > 0
              && a.maxDecisions() <= 20
              && a.visibleEntityIds() != null
              && ids.containsAll(a.visibleEntityIds())
              && !a.visibleEntityIds().isEmpty(),
          "Invalid agent visibility/budget");
      require(
          a.allowedActions() != null
              && domains.get(w.domainCode()).actions().stream()
                  .map(action -> String.valueOf(action.get("actionType")))
                  .toList()
                  .containsAll(a.allowedActions()),
          "Unknown action permission");
    }
    require(Json.write(s).length() < 200000, "Scenario configuration too large");
    if (s.id() != null) scenario(s.id());
    var result =
        new Scenario(
            s.id() == null ? UUID.randomUUID().toString() : s.id(),
            s.name(),
            s.worldId(),
            List.copyOf(s.agents()),
            s.maxSteps(),
            s.maxEvents());
    db.update(
        "INSERT INTO scenario VALUES (?,?) ON CONFLICT(id) DO UPDATE SET"
            + " definition_json=excluded.definition_json",
        result.id(),
        Json.write(result));
    return result;
  }

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalArgumentException(message);
  }
}

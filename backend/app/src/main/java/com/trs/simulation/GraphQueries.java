package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.util.*;
import org.jgrapht.alg.scoring.BetweennessCentrality;
import org.jgrapht.alg.shortestpath.BFSShortestPath;
import org.jgrapht.graph.*;

/** Read-only algorithms over an authorized local graph, never global persistence. @author agent */
public final class GraphQueries {
  private GraphQueries() {}

  /** Finds directed shortest paths through active relations. @author agent */
  public static List<String> path(
      List<Entity> entities, List<Relation> relations, String from, String to) {
    var g = graph(entities, relations);
    if (!g.containsVertex(from) || !g.containsVertex(to)) return List.of();
    var p = new BFSShortestPath<>(g).getPath(from, to);
    return p == null ? List.of() : p.getVertexList();
  }

  /**
   * Computes degree and betweenness; bridge candidates are explanations, not facts. @author agent
   */
  public static Map<String, Object> metrics(List<Entity> entities, List<Relation> relations) {
    var graph = graph(entities, relations);
    Map<String, Integer> degree = new TreeMap<>();
    entities.forEach(e -> degree.put(e.id(), graph.degreeOf(e.id())));
    var centrality = new TreeMap<>(new BetweennessCentrality<>(graph).getScores());
    var bridges =
        centrality.entrySet().stream()
            .filter(e -> e.getValue() > 0)
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .toList();
    return Map.of(
        "evidence",
        "INFERRED",
        "degree",
        degree,
        "betweenness",
        centrality,
        "bridgeCandidates",
        bridges);
  }

  /** Bounded BFS used for impact estimates and local subgraphs. @author agent */
  public static List<String> reachable(
      List<Entity> entities, List<Relation> relations, String root, int depth) {
    if (depth < 0 || depth > 6) throw new IllegalArgumentException("Depth must be between 0 and 6");
    if (entities.stream().noneMatch(e -> e.id().equals(root)))
      throw new IllegalArgumentException("Entity is not visible");
    var seen = new TreeSet<String>();
    seen.add(root);
    for (int n = 0; n < depth; n++) {
      var previous = new HashSet<>(seen);
      relations.stream()
          .filter(e -> e.enabled() && previous.contains(e.sourceId()))
          .forEach(e -> seen.add(e.targetId()));
    }
    return List.copyOf(seen);
  }

  private static DefaultDirectedGraph<String, DefaultEdge> graph(
      List<Entity> entities, List<Relation> relations) {
    var g = new DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge.class);
    entities.forEach(e -> g.addVertex(e.id()));
    relations.stream()
        .filter(
            e -> e.enabled() && g.containsVertex(e.sourceId()) && g.containsVertex(e.targetId()))
        .forEach(e -> g.addEdge(e.sourceId(), e.targetId()));
    return g;
  }
}

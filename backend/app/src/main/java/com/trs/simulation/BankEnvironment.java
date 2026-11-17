package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Financial simulation plugin; exact integer minor units preserve money and never contact a
 * bank. @author agent
 */
@Component
public class BankEnvironment implements DomainEnvironment {
  public static final Set<String> ACTIONS =
      Set.of("TRANSFER", "FLAG_RISK", "ANALYZE_ACCOUNT", "END");

  /** Domain identity. @author agent */
  public String domainCode() {
    return "bank";
  }

  /** Validates exact initial money and currency invariants. @author agent */
  @Override
  public void validate(World world) {
    for (var e : world.entities())
      if (e.type().equals("BANK_ACCOUNT")) {
        try {
          if (!(e.properties().get("balanceMinor") instanceof Number))
            throw new IllegalArgumentException();
          long amount =
              new BigDecimal(String.valueOf(e.properties().get("balanceMinor"))).longValueExact();
          if (amount < 0
              || amount > 1_000_000_000_000L
              || !String.valueOf(e.properties().get("currency")).matches("[A-Z]{3}"))
            throw new IllegalArgumentException();
        } catch (Exception ex) {
          throw new IllegalArgumentException(
              "Bank accounts require nonnegative integer balanceMinor and three-letter currency");
        }
      }
  }

  /** Shows a generated transaction only when both accounts are already visible. @author agent */
  @Override
  public Set<String> observationScope(AgentDefinition agent, State state) {
    var visible = new HashSet<>(agent.visibleEntityIds());
    state.entities().stream()
        .filter(
            e ->
                e.type().equals("TRANSACTION")
                    && visible.contains(String.valueOf(e.properties().get("sourceId")))
                    && visible.contains(String.valueOf(e.properties().get("targetId"))))
        .forEach(e -> visible.add(e.id()));
    return visible;
  }

  /**
   * Checks permissions, account visibility, money precision and available funds atomically. @author
   * agent
   */
  public Outcome execute(AgentDefinition agent, Decision d, State state) {
    if (d == null
        || d.actionType() == null
        || !ACTIONS.contains(d.actionType())
        || !agent.allowedActions().contains(d.actionType())
        || d.parameters() == null
        || d.reason() == null
        || d.reason().length() > 1000)
      return reject(state, "Unknown, forbidden or malformed bank action");
    if (d.actionType().equals("END"))
      return d.parameters().isEmpty()
          ? new Outcome(true, "Task complete", state, false)
          : reject(state, "Unexpected END parameters");
    var target = account(state, d.targetId());
    if (target == null || !agent.visibleEntityIds().contains(target.id()))
      return reject(state, "Target account missing or outside visibility");
    if (d.actionType().equals("ANALYZE_ACCOUNT"))
      return d.parameters().isEmpty()
          ? new Outcome(true, "Read-only simulated account analysis", state, false)
          : reject(state, "Unexpected parameters");
    if (d.actionType().equals("FLAG_RISK")) {
      if (!d.parameters().isEmpty() || Boolean.TRUE.equals(target.properties().get("riskFlag")))
        return reject(state, "Invalid or already flagged account");
      var entities =
          state.entities().stream()
              .map(
                  e -> {
                    if (!e.id().equals(target.id())) return e;
                    var p = new HashMap<>(e.properties());
                    p.put("riskFlag", true);
                    p.put("riskEvidence", "SIMULATED");
                    return new Entity(e.id(), e.type(), e.name(), p);
                  })
              .toList();
      return change(state, entities, "Simulated risk flag created");
    }
    if (!d.parameters().keySet().equals(Set.of("sourceId", "amount", "currency")))
      return reject(state, "Transfer requires sourceId, amount and currency only");
    var source = account(state, String.valueOf(d.parameters().get("sourceId")));
    if (source == null
        || !agent.visibleEntityIds().contains(source.id())
        || source.id().equals(target.id())) return reject(state, "Invalid source account");
    if (!Objects.equals(source.properties().get("currency"), d.parameters().get("currency"))
        || !Objects.equals(target.properties().get("currency"), d.parameters().get("currency")))
      return reject(state, "Currency mismatch");
    if (state.entities().size() >= 200) return reject(state, "Entity budget reached");
    long amount;
    try {
      amount =
          new BigDecimal(String.valueOf(d.parameters().get("amount")))
              .movePointRight(2)
              .longValueExact();
    } catch (Exception e) {
      return reject(state, "Invalid amount precision or range");
    }
    if (amount <= 0 || amount > 1_000_000_000_000L)
      return reject(state, "Amount must be positive and within simulation limits");
    long debit = ((Number) source.properties().get("balanceMinor")).longValue(),
        credit = ((Number) target.properties().get("balanceMinor")).longValue();
    if (debit < amount || credit > Long.MAX_VALUE - amount)
      return reject(state, "Insufficient funds or balance overflow");
    var entities = new ArrayList<Entity>();
    for (var e : state.entities()) {
      if (e.id().equals(source.id()) || e.id().equals(target.id())) {
        var p = new HashMap<>(e.properties());
        p.put("balanceMinor", e.id().equals(source.id()) ? debit - amount : credit + amount);
        p.put("balanceEvidence", "SIMULATED");
        entities.add(new Entity(e.id(), e.type(), e.name(), p));
      } else entities.add(e);
    }
    entities.add(
        new Entity(
            "transaction_" + (state.version() + 1),
            "TRANSACTION",
            "Simulated transfer",
            Map.of(
                "sourceId",
                source.id(),
                "targetId",
                target.id(),
                "amountMinor",
                amount,
                "currency",
                d.parameters().get("currency"),
                "evidence",
                "SIMULATED")));
    return change(state, entities, "Simulated transfer committed; total funds conserved");
  }

  /** Publishes registered bank action parameter schemas. @author agent */
  public List<Map<String, Object>> actions() {
    return ACTIONS.stream()
        .sorted()
        .map(
            a ->
                Map.<String, Object>of(
                    "actionType",
                    a,
                    "parameters",
                    a.equals("TRANSFER")
                        ? Map.of(
                            "type",
                            "object",
                            "required",
                            List.of("sourceId", "amount", "currency"),
                            "additionalProperties",
                            false,
                            "properties",
                            Map.of(
                                "sourceId",
                                Map.of("type", "string"),
                                "amount",
                                Map.of("type", "string"),
                                "currency",
                                Map.of("type", "string")))
                        : Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(),
                            "additionalProperties",
                            false)))
        .toList();
  }

  /** Produces exact account totals and transfer/risk metrics. @author agent */
  public Map<String, Object> evaluate(State state) {
    var accounts = new TreeMap<String, Long>();
    state.entities().stream()
        .filter(e -> e.type().equals("BANK_ACCOUNT"))
        .forEach(
            e -> accounts.put(e.id(), ((Number) e.properties().get("balanceMinor")).longValue()));
    return Map.of(
        "balancesMinor",
        accounts,
        "totalMinor",
        accounts.values().stream().mapToLong(Long::longValue).sum(),
        "transferCount",
        state.entities().stream().filter(e -> e.type().equals("TRANSACTION")).count(),
        "evidence",
        "SIMULATED");
  }

  private Entity account(State state, String id) {
    return state.entities().stream()
        .filter(e -> e.type().equals("BANK_ACCOUNT") && e.id().equals(id))
        .findFirst()
        .orElse(null);
  }

  private Outcome reject(State state, String reason) {
    return new Outcome(false, reason, state, false);
  }

  private Outcome change(State state, List<Entity> entities, String reason) {
    return new Outcome(
        true,
        reason,
        new State(
            state.runId(),
            state.simulationTime(),
            state.version() + 1,
            List.copyOf(entities),
            state.relations()),
        true);
  }
}

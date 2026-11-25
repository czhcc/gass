package com.trs.simulation;

import com.trs.simulation.SimulationTypes.ActionCapability;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Outcome;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.World;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Bank domain adapter with exact-money invariants and declarative CEL actions. @author czhcc-trsdsj\czhcc */
@Component
public class BankEnvironment implements DomainEnvironment {
    public static final Set<String> ACTIONS =
            Set.of("TRANSFER", "FLAG_RISK", "ANALYZE_ACCOUNT", "ASSESS_RISK_CEL", "END");
    private final DeclarativeActionEngine engine = new DeclarativeActionEngine();

    /** Identifies the bank simulation domain. @author czhcc-trsdsj\czhcc */
    public String domainCode() {
        return "bank";
    }

    /** Validates account data and the bounded declarative action ontology. @author czhcc-trsdsj\czhcc */
    @Override
    public void validate(World world) {
        ActionPrograms.validate(world.ontology());
        for (var e : world.entities())
            if (e.type().equals("BANK_ACCOUNT")) {
                try {
                    if (!(e.properties().get("balanceMinor") instanceof Number)) throw new IllegalArgumentException();
                    long amount =
                            new BigDecimal(e.properties().get("balanceMinor").toString()).longValueExact();
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

    /** Shows generated transactions only to agents who can see both accounts. @author czhcc-trsdsj\czhcc */
    @Override
    public Set<String> observationScope(AgentDefinition agent, State state) {
        var visible = new HashSet<>(agent.visibleEntityIds());
        state.entities().stream()
                .filter(e -> e.type().equals("TRANSACTION")
                        && visible.contains(e.properties().get("sourceId"))
                        && visible.contains(e.properties().get("targetId")))
                .forEach(e -> visible.add(e.id()));
        return visible;
    }

    /** Preserves direct calls through the same declarative engine. @author czhcc-trsdsj\czhcc */
    public Outcome execute(AgentDefinition agent, Decision decision, State state) {
        return engine.execute(Examples.bankTransfer(), agent, decision, state);
    }

    /** Executes frozen definitions including custom actions. @author czhcc-trsdsj\czhcc */
    @Override
    public Outcome execute(World world, AgentDefinition agent, Decision decision, State state) {
        return engine.execute(world, agent, decision, state);
    }

    /** Publishes bank object capabilities from the shared engine. @author czhcc-trsdsj\czhcc */
    @Override
    public List<ActionCapability> capabilities(World world, AgentDefinition agent, State state) {
        return engine.capabilities(world, agent, state);
    }

    /** Publishes built-in schemas; custom permissions are resolved from world bindings. @author czhcc-trsdsj\czhcc */
    public List<Map<String, Object>> actions() {
        var result = new ArrayList<Map<String, Object>>();
        ActionPrograms.ontology("bank")
                .types()
                .forEach(t -> t.actions()
                        .forEach(a ->
                                result.add(Map.of("actionType", a.actionType(), "parameters", a.parametersSchema()))));
        result.add(Map.of("actionType", "END", "parameters", Map.of("type", "object", "properties", Map.of())));
        return result;
    }

    /** Computes financial totals without changing graph state. @author czhcc-trsdsj\czhcc */
    public Map<String, Object> evaluate(State state) {
        var accounts = new TreeMap<String, Long>();
        state.entities().stream()
                .filter(e -> e.type().equals("BANK_ACCOUNT"))
                .forEach(e -> accounts.put(e.id(), ((Number) e.properties().get("balanceMinor")).longValue()));
        return Map.of(
                "balancesMinor",
                accounts,
                "totalMinor",
                accounts.values().stream().mapToLong(Long::longValue).sum(),
                "transferCount",
                state.entities().stream()
                        .filter(e -> e.type().equals("TRANSACTION"))
                        .count(),
                "evidence",
                "SIMULATED");
    }
}

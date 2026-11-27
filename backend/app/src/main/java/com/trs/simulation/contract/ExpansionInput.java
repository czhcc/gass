package com.trs.simulation.contract;

/** Converts an API configuration to the snapshot stored with a scenario. @author czhcc-trsdsj\codexsandboxoffline */
@FunctionalInterface
public interface ExpansionInput {
    /** Returns the serializable, implementation-independent snapshot. @author czhcc-trsdsj\codexsandboxoffline */
    FrozenExpansion expansionSnapshot();
}

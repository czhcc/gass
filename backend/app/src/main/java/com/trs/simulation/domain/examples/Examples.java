package com.trs.simulation.domain.examples;

import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.World;
import java.util.List;
import java.util.Map;

/** Input facts and hypotheses only; contains no scripted agent decisions. @author agent */
public final class Examples {
    private Examples() {}

    /** Returns the document-leak template without asserting actual propagation. @author agent */
    public static World informationLeak() {
        return new World(
                null,
                "敏感信息泄露 / Information leak",
                "information",
                List.of("DOCUMENT", "PERSON", "SYSTEM", "ORGANIZATION"),
                List.of("ACCESSED_BY", "STORED_IN", "SHARED_WITH", "SYNCS_TO", "CONTACTS"),
                List.of(
                        entity("document_f", "DOCUMENT", "文件 F"),
                        entity("person_a", "PERSON", "人员 A"),
                        entity("person_b", "PERSON", "人员 B"),
                        entity("person_c", "PERSON", "人员 C"),
                        entity("system_s", "SYSTEM", "系统 S"),
                        entity("system_t", "SYSTEM", "系统 T"),
                        entity("organization_d", "ORGANIZATION", "机构 D")),
                List.of(
                        edge("access_a", "document_f", "person_a", "ACCESSED_BY"),
                        edge("access_b", "document_f", "person_b", "ACCESSED_BY"),
                        edge("stored_s", "document_f", "system_s", "STORED_IN"),
                        edge("share_ac", "person_a", "person_c", "SHARED_WITH"),
                        edge("sync_st", "system_s", "system_t", "SYNCS_TO"),
                        edge("contact_cd", "person_c", "organization_d", "CONTACTS")),
                1);
    }

    private static Entity entity(String id, String type, String name) {
        return new Entity(id, type, name, Map.of("reviewed", false));
    }

    private static Relation edge(String id, String from, String to, String type) {
        return new Relation(
                id, from, to, type, Map.of(), true, "FACT", "example input; connectivity is not propagation evidence");
    }

    /** Minimal financial world; amounts use exact currency minor units. @author agent */
    public static World bankTransfer() {
        return new World(
                null,
                "银行转账 / Bank transfer",
                "bank",
                List.of("BANK_ACCOUNT", "TRANSACTION"),
                List.of("TRANSFER_TO"),
                List.of(
                        new Entity(
                                "account_a",
                                "BANK_ACCOUNT",
                                "账户 A",
                                Map.of("balanceMinor", 1000000000L, "currency", "CNY")),
                        new Entity(
                                "account_b",
                                "BANK_ACCOUNT",
                                "账户 B",
                                Map.of("balanceMinor", 200000000L, "currency", "CNY"))),
                List.of(new Relation(
                        "transfer_ab",
                        "account_a",
                        "account_b",
                        "TRANSFER_TO",
                        Map.of(),
                        true,
                        "FACT",
                        "example input")),
                1);
    }
}

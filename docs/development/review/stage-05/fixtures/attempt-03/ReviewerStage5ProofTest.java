package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.*;
import com.trs.simulation.common.Json;
import com.trs.simulation.domain.examples.Examples;
import com.trs.simulation.evidence.ObjectiveEvidence.CompletionCheck;
import com.trs.simulation.evidence.ObjectiveProof;
import com.trs.simulation.graph.KnowledgeCoverage;
import com.trs.simulation.knowledge.*;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Operation;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Query;
import com.trs.simulation.model.ModelService;
import com.trs.simulation.world.WorldService;
import com.trs.studio.StudioApplication;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import static org.junit.jupiter.api.Assertions.*;

/** Independent recorded evidence using an isolated read-only registry. @author czhcc-trsdsj\codexsandboxoffline */
@SpringBootTest(classes={StudioApplication.class,ReviewerStage5ProofTest.Registry.class},
    properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.datasource.hikari.maximum-pool-size=1"})
class ReviewerStage5ProofTest {
    @Autowired WorldService worlds;
    @Autowired RunService runs;
    @Autowired ModelService models;
    @Autowired KnowledgeExpansionService expansion;

    @Test
    void matchingPointLookupIsEnoughAndStaleOrUnrelatedProofIsRejected() throws Exception {
        var world=worlds.create(Examples.bankTransfer());
        var model=models.mock().id();
        var historyId="case-187";
        var present=new Query(Operation.GET_ENTITY,historyId,0,1);
        var absent=new Query(Operation.GET_ENTITY,"nothing-there",0,1);
        var search=new Query(Operation.SEARCH,"registry",0,1);
        var mapping=new OntologyMapping("audit-registry",2,
            Map.of("account",new OntologyMapping.EntityType("BANK_ACCOUNT",Map.of())),Map.of());
        var source=new ExpansionPolicy.Source(mapping,SubgraphImport.Identities.empty(),
            Map.of(historyId,Set.of("lookup","reviewer")),Map.of(),Map.of("lookup",Set.of(present,absent,search)));
        var policy=new ExpansionPolicy(Set.of("owner"),Map.of("audit-registry",source),9,2,4,4,8);
        var people=List.of(new AgentDefinition("reviewer","Review","Confirm",model,List.of(),List.of("account_a"),4,new AgentRuntime("MASTER","review_scope","Review the evidence",List.of("lookup"))),
            new AgentDefinition("lookup","Lookup","Read",model,List.of(),List.of("account_a"),4,new AgentRuntime("QUERY","delegate_lookup","Read assigned scope",List.of("graph_get_entity"))));
        var procedure=new EvaluationPolicy("TEXT_ONLY",1,0L,null,null,true,"OBJECTIVE","EXTERNAL_GRAPH",
            new ExternalExistence("audit-registry",historyId));
        var scene=worlds.scenario(new Scenario(null,"Scope review",world.id(),people,6,120,new TeamConfig("reviewer",2,1,List.of(),List.of()),
            "Confirm that record case-187 exists in the named registry.",new SchedulingPolicy(1,"EVENT_DRIVEN","TICK",20),procedure,null,policy));
        var run=runs.create(scene.id()).id();
        var task=new KnowledgeExpansionService.TaskScope("independent-task","lookup","reviewer",Set.of("account_a"),false,()->{});
        expansion.queryForTask(run,task,"audit-registry",present);
        var proof=runs.events(run,0).getLast();
        expansion.queryForTask(run,task,"audit-registry",absent);
        var missing=runs.events(run,0).getLast();
        expansion.queryForTask(run,task,"audit-registry",search);
        var differentOperation=runs.events(run,0).getLast();
        var state=runs.state(run);
        var context=new ObjectiveProof.Context(scene,state,state,Set.of("account_a"),"reviewer",new KnowledgeCoverage(run,0));
        var other=new ObjectiveProof.Context(scene,state,state,Set.of("account_a"),"different-reviewer",new KnowledgeCoverage(run,0));
        var stale=new ObjectiveProof.Context(scene,state,state,Set.of("account_a"),"reviewer",new KnowledgeCoverage(run,1));
        var history=runs.events(run,0);
        var results=new LinkedHashMap<String,Object>();
        results.put("matched",ObjectiveProof.assess(context,history,Set.of(proof.sequence())));
        results.put("missing",ObjectiveProof.assess(context,history,Set.of(missing.sequence())));
        results.put("search",ObjectiveProof.assess(context,history,Set.of(differentOperation.sequence())));
        results.put("wrongReader",ObjectiveProof.assess(other,history,Set.of(proof.sequence())));
        results.put("staleCoverage",ObjectiveProof.assess(stale,history,Set.of(proof.sequence())));
        Files.writeString(Path.of(System.getenv("REVIEWER_REPAIR_OUTPUT"),"external-witness.json"),
            Json.write(Map.of("goal",scene.objective(),"history",history,"results",results)));
        assertEquals("EXTERNAL_GRAPH_QUERY",proof.eventType());
        assertEquals("case-187",proof.payload().get("existenceWitness"));
        assertEquals("UNKNOWN",((Map<?,?>)proof.payload().get("coverage")).get("completeness"));
        assertEquals(CompletionCheck.SUPPORTED,((ObjectiveProof.Assessment)results.remove("matched")).check());
        for(var entry:results.entrySet()) {
            assertEquals(CompletionCheck.EVIDENCE_UNAVAILABLE,((ObjectiveProof.Assessment)entry.getValue()).check(),entry.getKey());
        }
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class Registry {
        @Bean
        KnowledgeGraphProvider independentRegistry(){
            return new KnowledgeGraphProvider(){
                public String providerCode(){return "audit-registry";}
                public ExternalSubgraph query(Query query){
                    var found=query.selector().equals("nothing-there") ? List.<ExternalSubgraph.ExternalEntity>of() :
                        List.of(new ExternalSubgraph.ExternalEntity("case-187","account","Isolated fixture",Map.of()));
                    return new ExternalSubgraph("review-r19","2026-05-27T03:02:00Z",
                        new ExternalSubgraph.Coverage("synthetic shard","UNKNOWN",false,false),found,List.of());
                }
            };
        }
    }
}

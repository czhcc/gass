package com.trs.studio;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.common.Json;
import com.trs.simulation.agent.AgentScopeDecisionProvider;
import com.trs.simulation.runtime.RunService;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Independent authenticated HTTP scenario for an answered objective with pending work. @author czhcc-trsdsj\codexsandboxoffline */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"studio.admin-password=${REVIEWER_FIXTURE_PASSWORD}","spring.datasource.url=jdbc:sqlite::memory:","spring.datasource.hikari.maximum-pool-size=1"})
class ReviewerStage5NaturalScopeTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired RunService runs;
    @MockitoBean AgentScopeDecisionProvider decisions;
    final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(10)).build();
    @Autowired com.trs.simulation.model.ModelService models;
    @Autowired com.trs.simulation.world.WorldService worlds;
    @Autowired com.trs.simulation.scenario.ScenarioProfiles profiles;

    record Sample(String name, String declared, String goal, boolean mayComplete, boolean pointQuery) {
        @Override public String toString(){ return name; }
    }

    static java.util.stream.Stream<Sample> samples(){
        return java.util.stream.Stream.of(
            new Sample("known-bounded-ranking",null,"Rank paths within the current authorized run graph.",true,false),
            new Sample("paraphrased-bounded-ranking",null,"Produce a ranking of paths restricted to the current authorized run graph.",true,false),
            new Sample("chinese-bounded-ranking",null,"只对当前授权运行图谱中的路径进行排名。",true,false),
            new Sample("timed-bounded-ranking",null,"At tick 0, rank paths within the current authorized run graph.",true,false),
            new Sample("structured-local","RUN_GRAPH","Compare people only within the current authorized run graph.",true,false),
            new Sample("grounded-observation",null,"Inspect the document_f.",true,true),
            new Sample("mismatched-observation",null,"Inspect the document_f.",false,false),
            new Sample("provider-implicit-maximum",null,"Which person wins the ranking when the comparison includes everything the register holds?",false,false),
            new Sample("unknown-network",null,"Who is closest in the entire network, including any records we have yet to discover?",false,false),
            new Sample("timed-implicit-maximum",null,"At tick 0, who wins if nothing kept in the source catalogue is excluded?",false,false),
            new Sample("external-explicit","EXTERNAL_GRAPH","Rank paths for every person held by the source.",false,false),
            new Sample("mixed-local-then-provider",null,"Start from the current authorized run graph and include the rest of the data provider's records in the comparison.",false,false),
            new Sample("structured-local-conflicts-with-goal","RUN_GRAPH","At tick 0, find the closest person across every record held by the registered data provider, including records absent from this snapshot.",false,false),
            new Sample("structured-local-explicit-external-conflict","RUN_GRAPH","Rank paths in the entire external graph.",false,false)
        );
    }

    /** Submits real tool calls to the same event-driven HTTP API with language and scope varied independently. */
    @org.junit.jupiter.params.ParameterizedTest(name="{0}")
    @org.junit.jupiter.params.provider.MethodSource("samples")
    void scopedObjective(Sample sample) throws Exception {
        boolean failReport=false;
        String declared=sample.declared();
        String goal=sample.goal();
        String answer="A and B lead across every record held by the provider, including those absent from this snapshot.";
        post("/auth/login",Map.of("username","admin","password",System.getenv("REVIEWER_FIXTURE_PASSWORD")),null,200);
        var world=post("/examples/information-leak",Map.of(),null,200);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var references=new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var activeRun=new java.util.concurrent.atomic.AtomicReference<String>();
        var provider=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/v1/chat/completions", exchange -> {
            try {
                var request=Json.read(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),Map.class);
                String input=Json.write(request.get("messages"));
                String tool;
                Object args;
                Map<String,Object> message;
                String finish;
                if(input.contains("endGraph") && input.contains("historicalActions")) {
                    if(failReport){
                        exchange.sendResponseHeaders(502,-1);
                        exchange.close();
                        return;
                    }
                    tool="generate_response";
                    args=Map.of("response",Map.of("conclusion","Current graph checked",
                        "evidenceSequences",List.of(references.getLast()),"limitations","Review fixture"));
                } else {
                    int phase=calls.getAndIncrement()%5;
                    switch(phase) {
                        case 0 -> {tool="delegate_research"; args=Map.of("goal","Inspect the current document");}
                        case 1 -> {tool="graph_query"; args=sample.pointQuery()
                            ? Map.of("tool","graph_get_entity","arguments",Map.of("entityId","document_f"))
                            : Map.of("tool","graph_path","arguments",Map.of("from","document_f","rankByType","PERSON"));}
                        case 2 -> {tool=null; args=null;}
                        case 3 -> {
                            tool="complete_objective";
                            String id=activeRun.get();
                            long current=runs.state(id).version();
                            long seq=runs.events(id,0).reversed().stream().filter(e->e.eventType().equals("GRAPH_QUERY_RESULT")
                                && e.payload().get("result") instanceof Map<?,?> row
                                && ((Number)row.get("version")).longValue()==current).findFirst().orElseThrow().sequence();
                            references.add(seq);
                            args=Map.of("summary",answer,"evidenceSequences",List.of(seq));
                        }
                        default -> {tool="generate_response"; args=Map.of("response",Map.of("summary","Reviewed","findings",List.of(),"nextTasks",List.of()));}
                    }
                }
                if(tool==null) {
                    message=Map.of("role","assistant","content","The current document was observed.");
                    finish="stop";
                } else {
                    message=Map.of("role","assistant","tool_calls",List.of(Map.of("id","review-call-"+calls.get(),
                        "type","function","function",Map.of("name",tool,"arguments",Json.write(args)))));
                    finish="tool_calls";
                }
                var data=Json.write(Map.of("id","review-response-"+calls.get(),"object","chat.completion","created",1,
                    "model","fixture","choices",List.of(Map.of("index",0,"finish_reason",finish,"message",message))))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,data.length);
                try(var out=exchange.getResponseBody()){out.write(data);}
            } catch(RuntimeException ex) {
                var data=ex.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500,data.length);
                try(var out=exchange.getResponseBody()){out.write(data);}
            }
        });
        provider.start();
        try {
            var model=models.save(new com.trs.simulation.model.ModelService.Input("reviewer-provider",
                "http://127.0.0.1:"+provider.getAddress().getPort()+"/v1","fixture",null,null,0,1024,5,1));
            var configured=profiles.scenario("information",worlds.get((String)world.get("id")),model.id(),"review phase 3",6);
            var p=configured.team();
            var mapping=new com.trs.simulation.knowledge.OntologyMapping("unconfigured-test-source",1,Map.of(),Map.of());
            var source=new com.trs.simulation.knowledge.ExpansionPolicy.Source(mapping,
                com.trs.simulation.knowledge.SubgraphImport.Identities.empty());
            var policy=new com.trs.simulation.knowledge.ExpansionPolicy(Set.of("admin"),
                Map.of("unconfigured-test-source",source),4,1,4,4,4);
            var scene=new com.trs.simulation.SimulationTypes.Scenario(null,configured.name(),configured.worldId(),
                configured.agents(),6,1000,new com.trs.simulation.SimulationTypes.TeamConfig(p.rootAgentId(),
                    p.maxDelegationsPerRound(),p.maxActionsPerRound(),p.mockRules(),List.of(),p.inputActions()),
                goal,
                new com.trs.simulation.SimulationTypes.SchedulingPolicy(1,"EVENT_DRIVEN","TICK",24),
                new com.trs.simulation.SimulationTypes.EvaluationPolicy("TEXT_ONLY",1,0L,null,null,false,"OBJECTIVE",declared),null,policy);
            var registered=post("/scenarios",Json.read(Json.write(scene),Map.class),null,200);
            var created=post("/scenarios/"+registered.get("id")+"/runs",Map.of(),null,200);
            String id=(String)created.get("id");
            activeRun.set(id);
            String path="/runs/"+id;
            post(path+"/step",Map.of(),"attempt-global-completion",200);
            var graphEvent=runs.events(id,0).stream().filter(e->e.eventType().equals("GRAPH_QUERY_RESULT"))
                .findFirst().orElseThrow();
            var graph=(Map<?,?>)graphEvent.payload().get("result");
            var local=(Map<?,?>)graph.get("pathRanking");
            assertEquals("UNKNOWN",graph.get("externalCoverage"));
            if(!sample.pointQuery()) assertEquals(true,local.get("complete"),local.toString());
            var recorded=runs.events(id,0).stream().filter(e->e.eventType().equals("OBJECTIVE_COMPLETION_RECORDED")).toList();
            System.out.println(Json.write(Map.of("graphAudit",graphEvent,"savedCompletion",recorded,"run",get(path),"outcome",get(path+"/outcome"))));
            var deferrals=runs.events(id,0).stream().filter(e->e.eventType().equals("OBJECTIVE_COMPLETION_DEFERRED")).toList();
            var evaluations=runs.events(id,0).stream().filter(e->e.eventType().equals("SCENARIO_EVALUATED")).toList();
            var run=get(path);
            var outcome=get(path+"/outcome");
            var storedScene=get("/scenarios/"+registered.get("id"));
            var trace=new LinkedHashMap<String,Object>();
            trace.put("case",sample.name());
            trace.put("requestGoal",goal);
            trace.put("declaredScope",Objects.toString(declared,"unset"));
            trace.put("modelClaim",answer);
            trace.put("savedScenario",storedScene);
            trace.put("evidenceReferences",references);
            trace.put("graphAudit",graphEvent);
            trace.put("savedCompletion",recorded);
            trace.put("deferred",deferrals);
            trace.put("evaluations",evaluations);
            trace.put("run",run);
            trace.put("outcome",outcome);
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("REVIEWER_REPAIR_OUTPUT"),"natural-"+sample.name()+".json"),
                Json.write(trace));
            assertFalse(references.isEmpty(),"Model must attempt an actual completion");
            if(sample.mayComplete()){
                assertEquals(1,recorded.size(),"Equivalent, explicitly local aim with matching complete evidence should complete");
                assertEquals("RUN_GRAPH",recorded.getFirst().payload().get("goalScope"));
                assertEquals("OBJECTIVE_REVIEW_COMPLETED",run.get("terminationReason"));
                assertFalse(Json.write(recorded).contains(answer),"The persisted conclusion must be generated from the evidence");
                assertFalse(Json.write(outcome).contains(answer),"The final response must not echo the broader model claim");
            } else {
                assertTrue(recorded.isEmpty(),"An external, unresolved or unmatched requirement cannot complete from this evidence");
                assertFalse(deferrals.isEmpty());
                assertNotEquals("OBJECTIVE_REVIEW_COMPLETED",run.get("terminationReason"));
            }
        } finally {
            provider.stop(0);
        }
    }


    private URI uri(String path){return URI.create("http://127.0.0.1:"+port+"/api"+path);}
    private Map<?,?> get(String path) throws Exception {
        var response=http.send(HttpRequest.newBuilder(uri(path)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());
        return Json.read(response.body(),Map.class);
    }
    private Map<?,?> post(String path,Map<?,?> body,String key,int status) throws Exception {
        var request=HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(key!=null) request.header("Idempotency-Key",key);
        var response=http.send(request.POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(status,response.statusCode(),response.body());
        return response.body().isBlank()?Map.of():Json.read(response.body(),Map.class);
    }
}

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
class ReviewerStage5GlobalClaimTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired RunService runs;
    @MockitoBean AgentScopeDecisionProvider decisions;
    final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(10)).build();
    @Autowired com.trs.simulation.model.ModelService models;
    @Autowired com.trs.simulation.world.WorldService worlds;
    @Autowired com.trs.simulation.scenario.ScenarioProfiles profiles;

    @Test void aLocalSearchCannotCompleteAnExternalGlobalObjective() throws Exception {
        boolean failReport=false;
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
                        case 1 -> {tool="graph_query"; args=Map.of("tool","graph_path","arguments",Map.of("from","document_f","rankByType","PERSON"));}
                        case 2 -> {tool=null; args=null;}
                        case 3 -> {
                            tool="complete_objective";
                            String id=activeRun.get();
                            long current=runs.state(id).version();
                            long seq=runs.events(id,0).reversed().stream().filter(e->e.eventType().equals("GRAPH_QUERY_RESULT")
                                && e.payload().get("result") instanceof Map<?,?> row
                                && ((Number)row.get("version")).longValue()==current).findFirst().orElseThrow().sequence();
                            references.add(seq);
                            args=Map.of("summary","Person A and Person B are definitively the closest across the entire external graph.","evidenceSequences",List.of(seq));
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
                "Find the closest person across the entire external graph, including knowledge beyond this local run",
                new com.trs.simulation.SimulationTypes.SchedulingPolicy(1,"EVENT_DRIVEN","TICK",24),
                new com.trs.simulation.SimulationTypes.EvaluationPolicy("TEXT_ONLY",1,0L,null,null,false,"OBJECTIVE"),null,policy);
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
            assertEquals(true,local.get("complete"),local.toString());
            var recorded=runs.events(id,0).stream().filter(e->e.eventType().equals("OBJECTIVE_COMPLETION_RECORDED")).toList();
            System.out.println(Json.write(Map.of("graphAudit",graphEvent,"savedCompletion",recorded,"run",get(path),"outcome",get(path+"/outcome"))));
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("REVIEWER_STAGE5_OUTPUT"),"global-objective-scope.json"),
                Json.write(Map.of("graphAudit",graphEvent,"savedCompletion",recorded,"run",get(path),"outcome",get(path+"/outcome"))));
            assertTrue(recorded.stream().noneMatch(e->String.valueOf(e.payload().get("summary")).contains("definitively the closest across the entire external graph")),
                "A local completed path query with UNKNOWN external coverage cannot justify an unqualified global completion");
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

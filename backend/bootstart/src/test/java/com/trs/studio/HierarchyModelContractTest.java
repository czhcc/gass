package com.trs.studio;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.trs.simulation.*;
import com.trs.simulation.SimulationTypes.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Real OpenAI-compatible protocol fixture; verifies SDK nesting, not real LLM quality. @author czhcc-trsdsj\codexsandboxoffline */
@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.datasource.hikari.maximum-pool-size=1"})
class HierarchyModelContractTest {
  @Autowired ModelService models;
  @Autowired ScenarioProfiles profiles;
  @Autowired WorldService worlds;
  @Autowired RunService runs;

  @Test void configuredModelDelegatesAndReceivesQueryFeedbackWithConcurrencyOne() throws Exception {
    var calls=new AtomicInteger(); var requests=new CopyOnWriteArrayList<Map<String,Object>>();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/chat/completions",exchange->{
      requests.add(Json.read(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),Map.class));
      int call=calls.incrementAndGet(); Map<String,Object> message;
      if(call==3) message=Map.of("role","assistant","content","已读取当前图谱并返回查询证据。");
      else {
        String tool=call==1?"delegate_research":call==2?"graph_query":"generate_response";
        Object arguments=call==1?Map.of("message",Json.write(new TaskRequest("检查关联对象",List.of(),List.of(),null))):call==2?Map.of("tool","graph_subgraph","arguments",Map.of()):Map.of("response",new RoundReport("已核对查询证据",List.of("查询不修改状态"),List.of("继续委派对象操作")));
        message=Map.of("role","assistant","tool_calls",List.of(Map.of("id","call-"+call,"type","function","function",Map.of("name",tool,"arguments",Json.write(arguments)))));
      }
      byte[] bytes=Json.write(Map.of("id","fixture-"+call,"object","chat.completion","created",1,"model","fixture","choices",List.of(Map.of("index",0,"finish_reason",call==3?"stop":"tool_calls","message",message)))).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }); server.start();
    try {
      var model=models.save(new ModelService.Input("Fixture","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture",null,null,0,8192,3,1));
      var world=worlds.create(Examples.bankTransfer()); var scene=worlds.scenario(profiles.scenario("bank",world,model.id(),"Real protocol",4)); var run=runs.create(scene.id());
      runs.step(run.id(),"protocol");
      assertEquals("PAUSED",runs.get(run.id()).status(),Json.write(runs.events(run.id(),0)));
      assertEquals(4,calls.get()); assertEquals(4,((Number)runs.usage(run.id()).get("requests")).intValue());
      assertTrue(requests.stream().allMatch(body->Json.write(body).contains("8192")));
      var queryTools=(List<Map<String,Object>>)requests.get(1).get("tools");
      assertFalse(queryTools.stream().anyMatch(t->Json.write(t).contains("submit_action")));
      assertTrue(Json.write(requests.get(3)).contains("queryResults"));
      assertTrue(runs.events(run.id(),0).stream().filter(e->e.eventType().equals("AGENT_STARTED")).allMatch(e->"LLM".equals(e.payload().get("modelMode"))));
      assertTrue(runs.tasks(run.id()).stream().allMatch(t->t.status().equals("SUCCEEDED")));
      assertTrue(requests.stream().allMatch(body -> Json.write(body.get("messages")).contains("简体中文")), "Both master and worker prompts must require Chinese public reports");
      var worker = runs.tasks(run.id()).stream().filter(t -> t.parentTaskId() != null).findFirst().orElseThrow();
      assertEquals("已读取当前图谱并返回查询证据。", worker.result().get("summary"), "SDK session identifiers do not belong in public summaries");
    } finally { server.stop(0); }
  }

  @Test void pausingANestedNetworkRequestCancelsTheWholeTaskTree() throws Exception {
    var calls=new AtomicInteger(); var childEntered=new CountDownLatch(1); var release=new CountDownLatch(1);
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/chat/completions",exchange->{
      exchange.getRequestBody().readAllBytes(); int call=calls.incrementAndGet();
      if(call>1) { childEntered.countDown(); try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException ex){Thread.currentThread().interrupt();} }
      var message=call==1?Map.of("role","assistant","tool_calls",List.of(Map.of("id","delegate","type","function","function",Map.of("name","delegate_research","arguments",Json.write(Map.of("message",Json.write(new TaskRequest("Read",List.of(),List.of(),null)))))))):Map.of("role","assistant","content","Late answer");
      byte[] bytes=Json.write(Map.of("id","slow","object","chat.completion","created",1,"model","fixture","choices",List.of(Map.of("index",0,"finish_reason",call==1?"tool_calls":"stop","message",message)))).getBytes(StandardCharsets.UTF_8);
      try { exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes); } catch(java.io.IOException ignored) { /* The client is expected to cancel its connection. */ } finally {exchange.close();}
    }); server.start();
    try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
      var model=models.save(new ModelService.Input("Slow fixture","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture",null,null,0,1024,10,1));
      var world=worlds.create(Examples.bankTransfer());var run=runs.create(worlds.scenario(profiles.scenario("bank",world,model.id(),"Cancel tree",4)).id());
      var future=pool.submit(()->runs.step(run.id(),"slow"));assertTrue(childEntered.await(5,TimeUnit.SECONDS));
      runs.pause(run.id());future.get(5,TimeUnit.SECONDS);release.countDown();
      assertEquals("PAUSED",runs.get(run.id()).status());assertEquals(0,runs.state(run.id()).version());
      assertEquals(2,runs.tasks(run.id()).size());assertTrue(runs.tasks(run.id()).stream().allMatch(t->t.status().equals("CANCELLED")),Json.write(runs.tasks(run.id())));
    } finally {release.countDown();server.stop(0);}
  }
}

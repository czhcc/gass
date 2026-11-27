package com.trs.simulation.api;

import com.trs.simulation.knowledge.KnowledgeGraphProvider.Query;
import com.trs.simulation.runtime.KnowledgeExpansionService;
import com.trs.simulation.runtime.KnowledgeExpansionService.Candidate;
import com.trs.simulation.runtime.KnowledgeExpansionService.Provenance;
import com.trs.simulation.runtime.KnowledgeExpansionService.Receipt;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 运行操作人的只读查询和显式导入入口。 @author czhcc-trsdsj\codexsandboxoffline */
@RestController
@RequestMapping("/api/runs/{runId}/knowledge")
public class KnowledgeExpansionController {
    private final KnowledgeExpansionService service;

    /** 注入外部图谱运行服务。 @author czhcc-trsdsj\codexsandboxoffline */
    public KnowledgeExpansionController(KnowledgeExpansionService service) {
        this.service = service;
    }

    /** 查询指定已登记外部源的请求。 @author czhcc-trsdsj\codexsandboxoffline */
    public record Search(String providerCode, Query query) {}

    /** 确认导入时只接收服务端候选 ID。 @author czhcc-trsdsj\codexsandboxoffline */
    public record Import(String candidateId) {}

    /** 查询外部图谱，不修改运行图谱。 @author czhcc-trsdsj\codexsandboxoffline */
    @PostMapping("/query")
    public Candidate query(@PathVariable String runId, @RequestBody Search request, HttpSession session) {
        return service.query(runId, (String) session.getAttribute("username"), request.providerCode(), request.query());
    }

    /** 显式确认导入数据，与模拟业务 Action 分开。 @author czhcc-trsdsj\codexsandboxoffline */
    @PostMapping("/imports")
    public Receipt commit(@PathVariable String runId, @RequestBody Import request, HttpSession session) {
        return service.commit(runId, (String) session.getAttribute("username"), request.candidateId());
    }
    /** 供操作人预览服务端已完成的申请。 @author czhcc-trsdsj\codexsandboxoffline */
    @GetMapping("/candidates/{id}")
    public Candidate inspect(@PathVariable String runId, @PathVariable String id, HttpSession session) {
        return service.inspect(runId, (String) session.getAttribute("username"), id);
    }

    /** 拒绝申请并释放审批等待。 @author czhcc-trsdsj\codexsandboxoffline */
    @PostMapping("/candidates/{id}/reject")
    public void reject(@PathVariable String runId, @PathVariable String id, HttpSession session) {
        service.reject(runId, (String) session.getAttribute("username"), id);
    }

    /** 撤销指定导入对象的角色授权。 @author czhcc-trsdsj\codexsandboxoffline */
    public record Revocation(String roleId, String objectId) {}

    /** 原子撤销外部授权。 @author czhcc-trsdsj\codexsandboxoffline */
    @PostMapping("/grants/revoke")
    public Map<String, Long> revoke(@PathVariable String runId, @RequestBody Revocation input, HttpSession session) {
        return Map.of(
                "eventSequence",
                service.revoke(runId, (String) session.getAttribute("username"), input.roleId(), input.objectId()));
    }

    /** 查询指定对象的持久外部字段来源。 @author czhcc-trsdsj\codexsandboxoffline */
    @GetMapping("/provenance")
    public List<Provenance> provenance(@PathVariable String runId, @RequestParam String objectId, HttpSession session) {
        return service.provenance(runId, (String) session.getAttribute("username"), objectId);
    }
}

package com.trs.simulation.api;

import com.trs.simulation.knowledge.KnowledgeGraphProvider.Query;
import com.trs.simulation.runtime.KnowledgeExpansionService;
import com.trs.simulation.runtime.KnowledgeExpansionService.Candidate;
import com.trs.simulation.runtime.KnowledgeExpansionService.Receipt;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}

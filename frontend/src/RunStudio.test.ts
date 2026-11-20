import { describe, it, expect, vi, afterEach } from "vitest";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  cleanup,
  within,
} from "@testing-library/vue";
import RunStudio from "./components/RunStudio.vue";
vi.mock("./components/Graph.vue", () => ({
  __esModule: true,
  default: { template: '<button @click="$emit(\'select\', \'document_f\')">Select document F</button>' },
}));
vi.mock("./components/Comparison.vue", () => ({
  __esModule: true,
  default: { template: "<div>comparison</div>" },
}));
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it('shows readable key events and related objects, retaining all-event playback without computation', async () => {
  const run = { id: 'readable', scenarioId: 's', status: 'BUDGET_EXHAUSTED', terminationReason: 'MAX_STEPS', step: 6, maxSteps: 6, version: 1 };
  const entities = [{ id: 'a', type: 'PERSON', name: '人员 A', properties: {} }, { id: 'c', type: 'PERSON', name: '人员 C', properties: {} }];
  const state = { runId: run.id, entities, relations: [], simulationTime: 6, version: 1 };
  const events = [
    { runId: run.id, sequence: 17, simulationTime: 1, agentId: 'response', eventType: 'ACTION_PROPOSED', payload: { action: { actionType: 'REVOKE_SHARING', targetId: 'a', parameters: { recipientId: 'c' }, reason: 'Model original explanation' } } },
    { runId: run.id, sequence: 18, simulationTime: 1, agentId: 'response', eventType: 'ACTION_ACCEPTED', payload: { actionType: 'REVOKE_SHARING', targetId: 'a', affectedRelationIds: ['share'] } },
    { runId: run.id, sequence: 86, simulationTime: 6, agentId: null, eventType: 'RUN_BUDGET_EXHAUSTED', payload: { reasonCode: 'MAX_STEPS', actual: 6, limit: 6 } },
  ];
  const fetcher = vi.fn(async (url: string, _init?: RequestInit) => ({ ok: true, json: async () => url === '/api/runs' ? [run] : url.endsWith('/branches') ? [] : url.endsWith('/events') ? events
    : url.endsWith('/agents') ? [{ id: 'response', name: 'Response Agent', goal: 'Reduce simulated propagation risk using currently enabled channels. Review the updated state each round.', allowedActions: [] }]
    : url.endsWith('/action-capabilities') ? { agents: [] }
    : url.endsWith('/state') || url.includes('/replay?') ? state : run }));
  vi.stubGlobal('fetch', fetcher); vi.stubGlobal('EventSource', class { addEventListener() {} close() {} });
  render(RunStudio, { props: { lang: 'zh', initialId: run.id } });
  await fireEvent.click(await screen.findByRole('button', { name: /#18 行动已接受/ }));
  expect(screen.getByRole('heading', { name: '响应 Agent' })).toBeTruthy();
  const timeline = screen.getByRole('article', { name: '事件时间线' });
  const activity = screen.getByRole('complementary', { name: 'Agent 活动' });
  const graph = screen.getByRole('article', { name: '关联图' });
  expect(timeline.parentElement).toBe(activity.parentElement);
  expect(timeline.parentElement?.contains(graph)).toBe(false);
  expect(graph.compareDocumentPosition(timeline) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(screen.getByText('根据当前启用的通道降低模拟传播风险，每轮重新检查最新状态。')).toBeTruthy();
  expect(screen.getByText('Response Agent').closest('details')?.open).toBe(false);
  const selectedDetails = await screen.findByTestId('selected-event-details');
  expect(within(selectedDetails).getByRole('heading', { name: '行动已接受' })).toBeTruthy();
  expect(screen.getAllByText(/人员 A.*人员 C/).length).toBeGreaterThan(0);
  expect(screen.queryByRole('button', { name: /#17/ })).toBeNull();
  await fireEvent.update(screen.getByLabelText('事件范围'), 'all');
  await fireEvent.click(screen.getByRole('button', { name: /#17 行动等待校验/ }));
  await waitFor(() => expect(within(screen.getByTestId('selected-event-details')).getByRole('heading', { name: '行动等待校验' })).toBeTruthy());
  expect(fetcher.mock.calls.every(([url, init]) => init?.method === 'GET' && !url.endsWith('/step'))).toBe(true);
});
describe("historical playback", () => {
  it.each([
    ['BUDGET_EXHAUSTED', 'MAX_RUNTIME', '预算耗尽', '实际推演耗时达到上限'],
    ['COMPLETED', 'ALL_AGENTS_ENDED', '任务完成', '所有 Agent 均已成功结束任务'],
    ['COMPLETED', null, '历史已结束', '旧记录未区分任务完成与预算耗尽，无法确定具体结束原因'],
  ])('explains terminal status %s with reason %s', async (status, terminationReason, label, explanation) => {
    const run = { id: 'r1', scenarioId: 's1', status, terminationReason, activeRuntimeMs: 11000, step: 1, maxSteps: 5, version: 0 };
    vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => url === '/api/runs' ? [run]
      : url.endsWith('/branches') || url.endsWith('/agents') ? []
      : url.endsWith('/events') ? [{ runId: 'r1', sequence: 1, eventType: status === 'BUDGET_EXHAUSTED' ? 'RUN_BUDGET_EXHAUSTED' : 'RUN_COMPLETED', simulationTime: 1, agentId: null, payload: { reasonCode: terminationReason, actual: 11000, limit: 10000 } }]
      : url.endsWith('/state') || url.includes('/replay?') ? { runId: 'r1', entities: [], relations: [], version: 0, simulationTime: 1 }
      : url.endsWith('/action-capabilities') ? { domainCode: 'information', version: 0, agents: [] } : run })));
    vi.stubGlobal('EventSource', class { addEventListener() {} close() {} });
    render(RunStudio, { props: { lang: 'zh', initialId: 'r1' } });
    expect((await screen.findByTestId('termination-summary')).textContent).toContain(explanation);
    expect(screen.getByTestId('run-status').textContent).toContain(label);
    expect(screen.getByTestId('active-runtime').textContent).toContain('11.00');
    for (const name of ['计算单步', '继续计算', '停止运行'])
      expect((screen.getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(true);
  });
  it("uses engine-provided object actions and refreshes their state after manual execution", async () => {
    let version = 0;
    const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
      if (url.endsWith("/actions") && options?.method === "POST") version = 1;
      const state = { runId: "r1", simulationTime: 0, version, entities: [{ id: "document_f", type: "DOCUMENT", name: "文件 F", properties: { reviewed: version > 0 } }], relations: [] };
      const run = { id: "r1", scenarioId: "s", status: "PAUSED", step: 0, maxSteps: 5, version };
      const value = url === "/api/runs" ? [run] : url === "/api/branches" || url.endsWith("/events") ? []
        : url.endsWith("/agents") ? [{ id: "a", name: "Investigator", goal: "Review", modelId: "m", allowedActions: ["MARK_REVIEWED", "END"] }]
        : url.endsWith("/action-capabilities") ? { domainCode: "information", version, agents: [{ agentId: "a", capabilities: [{ targetKind: "ENTITY", targetId: "document_f", targetName: "文件 F", type: "DOCUMENT", available: version === 0, unavailableReason: version ? "Object is already reviewed" : "", sourceIds: [], action: { actionType: "MARK_REVIEWED", nameEn: "Mark reviewed", nameZh: "标记审查", description: "Review", parametersSchema: {}, preconditions: [], effect: "SET_REVIEWED" } }] }] }
        : url.endsWith("/state") || url.includes("/replay?") || url.endsWith("/actions") ? state : run;
      return { ok: true, json: async () => value };
    });
    vi.stubGlobal("fetch", fetcher);
    vi.stubGlobal("EventSource", class { addEventListener() {} close() {} });
    render(RunStudio, { props: { lang: "en", initialId: "r1" } });
    const execute = await screen.findByRole("button", { name: "Execute action" });
    await fireEvent.click(await screen.findByRole("button", { name: "Select document F" }));
    const details = await screen.findByRole("region", { name: "Object details" });
    expect(details.textContent).toContain("MARK_REVIEWED");
    expect(details.textContent).toContain("SET_REVIEWED");
    expect(screen.queryByLabelText("Parameters JSON")).toBeNull();
    await fireEvent.click(execute);
    await waitFor(() => expect(screen.getByTestId("world-version").textContent).toContain("1"));
    const submission = fetcher.mock.calls.find(([, options]) => options?.method === "POST")!;
    expect(submission[0]).toBe("/api/runs/r1/actions");
    expect(JSON.parse(String(submission[1]!.body))).toEqual({ agentId: "a", expectedVersion: 0, decision: {
      actionType: "MARK_REVIEWED", targetKind: "ENTITY", targetId: "document_f", parameters: {}, reason: "User-requested simulated action",
    } });
    expect((screen.getByRole("button", { name: "Execute action" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("seeks persisted history without triggering computation", async () => {
    const run = {
      id: "r1",
      scenarioId: "s1",
      status: "PAUSED",
      step: 1,
      maxSteps: 5,
      version: 1,
    };
    const data: Record<string, unknown> = {
      "/runs": [run],
      "/branches": [],
      "/runs/r1": run,
      "/runs/r1/agents": [],
      "/runs/r1/events": [
        {
          runId: "r1",
          sequence: 1,
          eventType: "WORLD_STATE_CHANGED",
          simulationTime: 1,
          agentId: "a",
          payload: { version: 1 },
        },
      ],
      "/runs/r1/state": {
        runId: "r1",
        simulationTime: 1,
        version: 1,
        entities: [],
        relations: [],
      },
      "/runs/r1/replay?sequence=0": {
        runId: "r1",
        simulationTime: 0,
        version: 0,
        entities: [],
        relations: [],
      },
      "/runs/r1/evaluation": {},
    };
    const fetcher = vi.fn().mockImplementation(async (url: string) => ({
      ok: true,
      json: async () => data[url.replace("/api", "")],
    }));
    vi.stubGlobal("fetch", fetcher);
    vi.stubGlobal(
      "EventSource",
      class {
        addEventListener() {}
        close() {}
        onerror = null;
      },
    );
    render(RunStudio, { props: { lang: "en", initialId: "r1" } });
    await waitFor(() =>
      expect(screen.getByTestId("world-version").textContent).toContain("1"),
    );
    await fireEvent.click(
      screen.getByRole("button", { name: "Previous event" }),
    );
    await waitFor(() =>
      expect(screen.getByTestId("world-version").textContent).toContain("0"),
    );
    expect(
      fetcher.mock.calls.every((call) => !String(call[0]).includes("/step")),
    ).toBe(true);
  });
});

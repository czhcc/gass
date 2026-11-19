import { describe, it, expect, vi, afterEach } from "vitest";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  cleanup,
} from "@testing-library/vue";
import RunStudio from "./components/RunStudio.vue";
vi.mock("./components/Graph.vue", () => ({
  __esModule: true,
  default: { template: "<div>graph</div>" },
}));
vi.mock("./components/Comparison.vue", () => ({
  __esModule: true,
  default: { template: "<div>comparison</div>" },
}));
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
describe("historical playback", () => {
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

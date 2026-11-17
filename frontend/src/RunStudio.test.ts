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

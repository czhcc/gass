import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/vue";
import RunStudio from "./components/RunStudio.vue";

vi.mock("./components/Graph.vue", () => ({ __esModule: true, default: { template: "<div />" } }));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

async function setup(lang: "zh" | "en" = "en", deletion?: Promise<unknown>, ids = ["run-one", "run-two"], initialId?: string) {
  let remaining = [...ids];
  const close = vi.fn();
  vi.stubGlobal("EventSource", class { close = close; addEventListener() {} });
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    if (options?.method === "DELETE") {
      const result = await (deletion ?? Promise.resolve({ ok: true, status: 204 }));
      if ((result as { ok: boolean }).ok) remaining = remaining.filter(id => url !== "/api/runs/" + id);
      return result;
    }
    const run = (id: string) => ({ id, scenarioId: "scene", status: "PAUSED", step: 0, maxSteps: 10, version: 0 });
    const value = url === "/api/runs" ? remaining.map(run)
      : url === "/api/branches" || url.endsWith("/events") || url.endsWith("/agents") ? []
      : url.endsWith("/state") || url.includes("/replay?")
        ? { runId: "run-one", entities: [], relations: [], version: 0, simulationTime: 0 }
        : run(url.split("/").at(-1)!);
    return { ok: true, json: async () => value };
  });
  vi.stubGlobal("fetch", fetcher);
  render(RunStudio, { props: { lang, initialId } });
  await screen.findByText("PAUSED", { selector: "span" });
  return { fetcher, close };
}

it.each([ ["en", "Delete run", "Confirm deletion", "Cancel"], ["zh", "删除运行", "确认删除", "取消"] ] as const)(
  "confirms deletion in a popup and selects the remaining run in %s", async (lang, remove, confirm, cancel) => {
    const { fetcher, close } = await setup(lang);
    const trigger = screen.getByRole("button", { name: remove });
    trigger.focus();
    await fireEvent.click(trigger);
    const dialog = screen.getByRole("dialog", { name: confirm });
    await waitFor(() => expect(document.activeElement).toBe(within(dialog).getByRole("button", { name: cancel })));
    await fireEvent.keyDown(dialog, { key: "Escape" });
    await waitFor(() => expect(document.activeElement).toBe(trigger));
    expect(fetcher.mock.calls.some(([, o]) => o?.method === "DELETE")).toBe(false);
    await fireEvent.click(trigger);
    await fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: confirm }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(fetcher).toHaveBeenCalledWith("/api/runs/run-one", expect.objectContaining({ method: "DELETE" }));
    await waitFor(() => expect((screen.getByRole("combobox", { name: lang === "zh" ? "运行 / 分支" : "Run / branch" }) as HTMLSelectElement).value).toBe("run-two"));
    expect(close).toHaveBeenCalled();
  });

it("keeps the dialog on branch conflicts and prevents duplicate submissions", async () => {
  let resolve!: (value: unknown) => void;
  const pending = new Promise<unknown>(done => { resolve = done; });
  const { fetcher } = await setup("en", pending);
  await fireEvent.click(screen.getByRole("button", { name: "Delete run" }));
  const dialog = screen.getByRole("dialog");
  const confirm = within(dialog).getByRole("button", { name: "Confirm deletion" });
  await fireEvent.keyDown(dialog, { key: "Tab" });
  expect(document.activeElement).toBe(confirm);
  await fireEvent.click(confirm);
  await fireEvent.click(confirm);
  await fireEvent.keyDown(dialog, { key: "Escape" });
  expect(screen.getByRole("dialog")).toBeTruthy();
  expect(fetcher.mock.calls.filter(([, o]) => o?.method === "DELETE")).toHaveLength(1);
  resolve({ ok: false, status: 409, json: async () => ({ code: "RUN_HAS_BRANCHES", message: "Blocked" }) });
  expect(await within(dialog).findByRole("alert")).toHaveProperty("textContent", "Delete child branches first.");
  expect(screen.getByRole("option", { name: /run-one/ })).toBeTruthy();
});

it("clears state when the last run is deleted", async () => {
  await setup("en", undefined, ["run-one"]);
  await fireEvent.click(screen.getByRole("button", { name: "Delete run" }));
  await fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm deletion" }));
  await screen.findByText("Create a run in Scenarios first");
  expect(screen.queryByRole("button", { name: "Compute one step" })).toBeNull();
  expect((screen.getByRole("button", { name: "Delete run" }) as HTMLButtonElement).disabled).toBe(true);
});

it("falls back to an existing run when the page URL still refers to a deleted run", async () => {
  const { fetcher } = await setup("en", undefined, ["run-two"], "deleted-run");
  expect((screen.getByRole("combobox", { name: "Run / branch" }) as HTMLSelectElement).value).toBe("run-two");
  expect(fetcher.mock.calls.some(([url]) => url.includes("/runs/deleted-run"))).toBe(false);
});

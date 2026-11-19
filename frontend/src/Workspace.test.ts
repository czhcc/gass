import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/vue";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import Workspace from "./components/Workspace.vue";
import type { World } from "./types";
import { useWorkspaceStore } from './stores/workspace';

vi.mock("./components/RunStudio.vue", () => ({
  __esModule: true,
  default: { template: "<div>Run viewer</div>" },
}));

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

async function setup(lang: "zh" | "en" = "en", deletion?: Promise<unknown>, worlds: World[] = []) {
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/scenarios' && options?.method === 'POST') return { ok: true, json: async () => ({ id: 'new', ...JSON.parse(String(options.body)) }) };
    if (options?.method === "DELETE") {
      return deletion ?? { ok: true, status: 204 };
    }
    if (url.endsWith("/demo")) return { ok: true, json: async () => ({ id: "demo-run" }) };
    return {
      ok: true,
      json: async () => url === "/api/scenarios"
        ? [{ id: "s1", name: "First scenario" }, { id: "s2", name: "Other scenario" }]
        : url === "/api/worlds" ? worlds : [],
    };
  });
  vi.stubGlobal("fetch", fetcher);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/:tab?", component: { render: () => null } }],
  });
  await router.push("/scenarios");
  await router.isReady();
  const pinia = createPinia();
  render(Workspace, { props: { lang }, global: { plugins: [pinia, router] } });
  await screen.findByText("First scenario");
  const card = screen.getByText("First scenario").closest("article")!;
  return { fetcher, card, router, store: useWorkspaceStore(pinia) };
}

it('explicitly grants selected custom world Actions to the primary default agent', async () => {
  const custom: World = { id: 'custom', name: 'Custom world', domainCode: 'bank', version: 1, entityTypes: ['BANK_ACCOUNT'], relationTypes: [], entities: [{ id: 'a', type: 'BANK_ACCOUNT', name: 'A', properties: {} }], relations: [], ontology: { version: 3, types: [{ targetKind: 'ENTITY', type: 'BANK_ACCOUNT', actions: [{ actionType: 'CUSTOM_SCORE', nameZh: '自定义评分', nameEn: 'Custom score', description: '', parametersSchema: {}, preconditions: [], effect: 'CUSTOM' }] }] } };
  const { fetcher, store } = await setup('en', undefined, [custom]);
  store.models = [{ id: 'm', name: 'Model', modelName: 'Model', baseUrl: 'mock://local', keyConfigured: false, apiKeyEnv: null, temperature: 0, maxTokens: 1024, timeoutSeconds: 10, concurrency: 1 }];
  await fireEvent.update(screen.getByLabelText('Scenario name'), 'Custom permission');
  await fireEvent.update(screen.getByLabelText('World'), 'custom');
  await fireEvent.update(screen.getByLabelText('Model'), 'm');
  await fireEvent.click(screen.getByLabelText('CUSTOM_SCORE · Custom score'));
  await fireEvent.click(screen.getByRole('button', { name: 'Create two-agent scenario' }));
  await waitFor(() => expect(fetcher.mock.calls.some(([url, init]) => url === '/api/scenarios' && init?.method === 'POST')).toBe(true));
  const body = JSON.parse(String(fetcher.mock.calls.find(([url, init]) => url === '/api/scenarios' && init?.method === 'POST')![1]!.body));
  expect(body.agents[0].allowedActions).toContain('CUSTOM_SCORE');
  expect(body.agents[1].allowedActions).not.toContain('CUSTOM_SCORE');
});

describe("demos use existing worlds", () => {
  const worlds: World[] = [
    { id: "leak-selected", name: "Selected leak world", domainCode: "information", entities: [], relations: [], entityTypes: [], relationTypes: [], version: 1 },
    { id: "bank-selected", name: "Selected bank world", domainCode: "bank", entities: [], relations: [], entityTypes: [], relationTypes: [], version: 1 },
  ];

  it("requires a world and sends its ID without importing another world", async () => {
    const { fetcher, router } = await setup("en", undefined, worlds);
    await fireEvent.click(screen.getByRole('link', { name: 'Demo world' }));
    await screen.findByLabelText('Demo world');
    const leak = screen.getByRole("button", { name: "Create leak demo" }) as HTMLButtonElement;
    const bank = screen.getByRole("button", { name: "Create bank demo" }) as HTMLButtonElement;
    expect(leak.disabled).toBe(true);
    expect(bank.disabled).toBe(true);
    await fireEvent.update(screen.getByLabelText("Demo world"), "leak-selected");
    expect(leak.disabled).toBe(false);
    expect(bank.disabled).toBe(true);
    await fireEvent.click(leak);
    await waitFor(() => expect(router.currentRoute.value.query.run).toBe("demo-run"));
    expect(fetcher).toHaveBeenCalledWith("/api/examples/information/demo", expect.objectContaining({
      method: "POST", body: JSON.stringify({ worldId: "leak-selected" }),
    }));
    expect(fetcher.mock.calls.some(([url, options]) => options?.method === "POST" && url !== "/api/examples/information/demo")).toBe(false);
  });

  it("enables only the matching bank demo for a bank world", async () => {
    const { fetcher } = await setup("en", undefined, worlds);
    await fireEvent.click(screen.getByRole('link', { name: 'Demo world' }));
    await screen.findByLabelText('Demo world');
    await fireEvent.update(screen.getByLabelText("Demo world"), "bank-selected");
    expect((screen.getByRole("button", { name: "Create leak demo" }) as HTMLButtonElement).disabled).toBe(true);
    await fireEvent.click(screen.getByRole("button", { name: "Create bank demo" }));
    await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/examples/bank/demo", expect.objectContaining({
      method: "POST", body: JSON.stringify({ worldId: "bank-selected" }),
    })));
  });

  it("explains where to create a world when none exist", async () => {
    await setup("zh");
    await fireEvent.click(screen.getByRole('link', { name: '演示世界' }));
    await screen.findByLabelText('演示世界');
    expect(screen.getByText("请先在“世界与图谱”中导入或创建世界，再选择世界创建演示。")).toBeTruthy();
    expect((screen.getByRole("button", { name: "创建泄密演示" }) as HTMLButtonElement).disabled).toBe(true);
  });
});

it('keeps six sidebar destinations and limits demo operations to their own page', async () => {
  const { router } = await setup();
  const navigation = screen.getByRole('navigation', { name: 'Workspace navigation' });
  expect(within(navigation).getAllByRole('link')).toHaveLength(6);
  expect(within(navigation).getByRole('link', { name: 'Scenarios' }).getAttribute('aria-current')).toBe('page');
  expect(screen.queryByLabelText('Demo world')).toBeNull();
  expect(screen.queryByRole('button', { name: 'Create leak demo' })).toBeNull();
  await fireEvent.click(within(navigation).getByRole('link', { name: 'Demo world' }));
  await screen.findByLabelText('Demo world');
  expect(router.currentRoute.value.path).toBe('/demos');
  expect(screen.queryByRole('button', { name: 'Create two-agent scenario' })).toBeNull();
  await fireEvent.click(within(navigation).getByRole('link', { name: 'Simulation & replay' }));
  await screen.findByText('Run viewer');
  expect(screen.queryByLabelText('Demo world')).toBeNull();
});

describe("scenario deletion", () => {
  it.each([
    ["en", "Delete", "Confirm deletion", "Cancel"],
    ["zh", "删除", "确认删除", "取消"],
  ] as const)("confirms or cancels deletion in %s", async (lang, remove, confirm, cancel) => {
    const { fetcher, card } = await setup(lang);
    const trigger = within(card).getByRole("button", { name: remove });
    trigger.focus();
    await fireEvent.click(trigger);
    const dialog = await screen.findByRole("dialog", { name: confirm });
    expect(dialog.getAttribute("aria-modal")).toBe("true");
    expect(card.contains(dialog)).toBe(false);
    expect(fetcher.mock.calls.some(([, options]) => options?.method === "DELETE")).toBe(false);
    await fireEvent.click(within(dialog).getByRole("button", { name: cancel }));
    expect(screen.queryByRole("dialog")).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(trigger));
    await fireEvent.click(within(card).getByRole("button", { name: remove }));
    await fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: confirm }));
    await waitFor(() => expect(screen.queryByText("First scenario")).toBeNull());
    expect(screen.getByText("Other scenario")).toBeTruthy();
    expect(fetcher).toHaveBeenCalledWith("/api/scenarios/s1", expect.objectContaining({ method: "DELETE" }));
  });

  it("blocks repeated deletion and retains the scenario on failure", async () => {
    let finish!: (response: unknown) => void;
    const deletion = new Promise((resolve) => { finish = resolve; });
    const { fetcher, card } = await setup("en", deletion);
    await fireEvent.click(within(card).getByRole("button", { name: "Delete" }));
    const dialog = await screen.findByRole("dialog");
    const confirm = within(dialog).getByRole("button", { name: "Confirm deletion" }) as HTMLButtonElement;
    await fireEvent.click(confirm);
    expect(confirm.disabled).toBe(true);
    await fireEvent.click(confirm);
    expect(fetcher.mock.calls.filter(([, options]) => options?.method === "DELETE")).toHaveLength(1);
    finish({ ok: false, status: 500, json: async () => ({ message: "Deletion failed" }) });
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("Deletion failed"));
    expect(screen.getByText("First scenario")).toBeTruthy();
    expect(confirm.disabled).toBe(false);
  });

  it("focuses the cancel button, traps Tab and closes with Escape without deleting", async () => {
    const { fetcher, card } = await setup();
    await fireEvent.click(within(card).getByRole("button", { name: "Delete" }));
    const dialog = await screen.findByRole("dialog");
    const cancel = within(dialog).getByRole("button", { name: "Cancel" });
    const confirm = within(dialog).getByRole("button", { name: "Confirm deletion" });
    await waitFor(() => expect(document.activeElement).toBe(cancel));
    await fireEvent.keyDown(cancel, { key: "Tab" });
    expect(document.activeElement).toBe(confirm);
    await fireEvent.keyDown(confirm, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(cancel);
    await fireEvent.keyDown(dialog, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(fetcher.mock.calls.some(([, options]) => options?.method === "DELETE")).toBe(false);
  });
});

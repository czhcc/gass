import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/vue";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import Workspace from "./components/Workspace.vue";
import type { World } from "./types";
import { useWorkspaceStore } from './stores/workspace';
import { readFileSync } from 'node:fs';
const profiles = JSON.parse(readFileSync('../backend/app/src/main/resources/scenario-profiles.json', 'utf8'));

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
        : url === '/api/scenario-profiles' ? profiles : url === "/api/worlds" ? worlds : [],
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
  await fireEvent.update(screen.getByLabelText('Maximum simulation rounds'), '13');
  await fireEvent.click(screen.getByLabelText('CUSTOM_SCORE · Custom score'));
  await fireEvent.click(screen.getByRole('button', { name: 'Create hierarchical scenario' }));
  await waitFor(() => expect(fetcher.mock.calls.some(([url, init]) => url === '/api/scenarios' && init?.method === 'POST')).toBe(true));
  const body = JSON.parse(String(fetcher.mock.calls.find(([url, init]) => url === '/api/scenarios' && init?.method === 'POST')![1]!.body));
  expect(body.agents.find((a: {id:string}) => a.id === 'bank').allowedActions).toContain('CUSTOM_SCORE');
  expect(body.agents[0].runtime.role).toBe('MASTER');
  expect(body.agents[0].allowedActions).toEqual([]);
  expect(body.team.rootAgentId).toBe('lead');
  expect(body.team.conditions.length).toBeGreaterThan(0);
  expect(body.maxSteps).toBe(13);
  expect(body.agents[1].allowedActions).not.toContain('CUSTOM_SCORE');
});

it('binds a published rule using the strict event contract', async () => {
  const world: World = { id: 'devices', name: 'Devices', domainCode: 'information', version: 1,
    entities: [], relations: [], entityTypes: [], relationTypes: [] };
  const { fetcher, store } = await setup('en', undefined, [world]);
  const original = fetcher.getMockImplementation()!;
  fetcher.mockImplementation(async (url, init) => url === '/api/rules/published?worldId=devices'
    ? { ok: true, json: async () => [{ id: 'rule-1', name: 'Propagation', scope: 'WORLD', revision: 1 }] }
    : original(url, init));
  store.models = [{ id: 'm', name: 'Model', modelName: 'Model', baseUrl: 'mock://local', keyConfigured: false,
    apiKeyEnv: null, temperature: 0, maxTokens: 1024, timeoutSeconds: 10, concurrency: 1 }];
  await fireEvent.update(screen.getByLabelText('Scenario name'), 'Propagation scenario');
  await fireEvent.update(screen.getByLabelText('World'), world.id);
  await fireEvent.update(screen.getByLabelText('Model'), 'm');
  await fireEvent.click(screen.getByRole('button', { name: 'Load published rules' }));
  await fireEvent.click(await screen.findByLabelText('Propagation · v1'));
  await fireEvent.click(screen.getByRole('button', { name: 'Create hierarchical scenario' }));
  await waitFor(() => expect(fetcher.mock.calls.some(([url, init]) => url === '/api/scenarios' && init?.method === 'POST')).toBe(true));
  const saved = JSON.parse(String(fetcher.mock.calls.find(([url, init]) => url === '/api/scenarios' && init?.method === 'POST')![1]!.body));
  expect(saved.ruleIds).toEqual(['rule-1']);
  expect(saved.scheduling).toEqual({ contractVersion: 1, mode: 'EVENT_DRIVEN', timeUnit: 'TICK' });
  expect(saved.team.rootAgentId).toBe('lead');
});

it('loads editable roles and outcome rules without losing the selected model or hierarchy',async()=>{
  const world:World={id:'bank-world',name:'Bank world',domainCode:'bank',version:1,entities:[{id:'a',type:'BANK_ACCOUNT',name:'A',properties:{}}],relations:[],entityTypes:['BANK_ACCOUNT'],relationTypes:[]};
  const {fetcher,store}=await setup('en',undefined,[world]);
  store.models=[{id:'chosen',name:'Chosen model',modelName:'Model',baseUrl:'mock://local',keyConfigured:false,apiKeyEnv:null,temperature:0,maxTokens:1024,timeoutSeconds:10,concurrency:1}];
  await fireEvent.update(screen.getByLabelText('Scenario name'),'Edited framework');
  await fireEvent.update(screen.getByLabelText('World'),world.id);
  const load=screen.getByRole('button',{name:'Load template for editing'}) as HTMLButtonElement; expect(load.disabled).toBe(true);
  await fireEvent.update(screen.getByLabelText('Model'),'chosen');await fireEvent.click(load);
  const roleEditor=screen.getByLabelText('Roles and prompts JSON (optional template override)');
  const configured=JSON.parse((roleEditor as HTMLTextAreaElement).value);configured[0].goal='Custom planning prompt';
  await fireEvent.update(roleEditor,JSON.stringify(configured));
  const teamEditor=screen.getByLabelText('Delegation and outcome conditions JSON (optional template override)');
  const team=JSON.parse((teamEditor as HTMLTextAreaElement).value);team.maxDelegationsPerRound=3;
  await fireEvent.update(teamEditor,JSON.stringify(team));
  await fireEvent.click(screen.getByRole('button',{name:'Create hierarchical scenario'}));
  await waitFor(()=>expect(fetcher.mock.calls.some(([url,init])=>url==='/api/scenarios'&&init?.method==='POST')).toBe(true));
  const saved=JSON.parse(String(fetcher.mock.calls.find(([url,init])=>url==='/api/scenarios'&&init?.method==='POST')![1]!.body));
  expect(saved.agents[0].goal).toBe('Custom planning prompt');expect(saved.agents.every((a:{modelId:string})=>a.modelId==='chosen')).toBe(true);
  expect(saved.team.maxDelegationsPerRound).toBe(3);expect(saved.agents.find((a:{id:string})=>a.id==='risk').allowedActions).toContain('ASSESS_RISK_CEL');
});

it('generates editable objective conditions and saves the edited rules with the natural-language goal', async () => {
  const world: World = { id: 'leak', name: 'Leak world', domainCode: 'information', version: 1, entityTypes: ['DOCUMENT'], relationTypes: [], relations: [], entities: [{ id: 'f', type: 'DOCUMENT', name: 'File F', properties: {} }] };
  const { fetcher, store } = await setup('en', undefined, [world]);
  const original = fetcher.getMockImplementation()!;
  fetcher.mockImplementation(async (url, options) => url === '/api/scenario-objectives/draft'
    ? { ok: true, json: async () => ({ mode: 'MOCK', unresolved: [], conditions: [{ id: 'check', nameZh: '文件已审查', nameEn: 'File reviewed', expression: 'false' }] }) }
    : original(url, options));
  store.models = [{ id: 'm', name: 'Model', modelName: 'Model', baseUrl: 'mock://local', keyConfigured: false, apiKeyEnv: null, temperature: 0, maxTokens: 1024, timeoutSeconds: 10, concurrency: 1 }];
  await fireEvent.update(screen.getByLabelText('Scenario name'), 'Assessment');
  await fireEvent.update(screen.getByLabelText('World'), world.id);
  await fireEvent.update(screen.getByLabelText('Model'), 'm');
  await fireEvent.update(screen.getByLabelText('Simulation objective'), 'Review file F by the deadline');
  await fireEvent.click(screen.getByRole('button', { name: 'Draft conditions' }));
  const expression = await screen.findByLabelText('Boolean condition (CEL)');
  await fireEvent.update(expression, 'entities.exists(e, e.id == "f" && has(e.properties.reviewed) && e.properties.reviewed)');
  await fireEvent.click(screen.getByRole('button', { name: 'Create hierarchical scenario' }));
  await waitFor(() => expect(fetcher.mock.calls.some(([url, init]) => url === '/api/scenarios' && init?.method === 'POST')).toBe(true));
  const saved = JSON.parse(String(fetcher.mock.calls.find(([url, init]) => url === '/api/scenarios' && init?.method === 'POST')![1]!.body));
  expect(saved.objective).toBe('Review file F by the deadline');
  expect(saved.team.conditions[0].expression).toContain('e.properties.reviewed');
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
    await fireEvent.update(screen.getByLabelText('Maximum simulation rounds'), '12');
    expect(leak.disabled).toBe(false);
    expect(bank.disabled).toBe(true);
    await fireEvent.click(leak);
    await waitFor(() => expect(router.currentRoute.value.query.run).toBe("demo-run"));
    expect(fetcher).toHaveBeenCalledWith("/api/examples/information/demo", expect.objectContaining({
      method: "POST", body: JSON.stringify({ worldId: "leak-selected", maxSteps: 12 }),
    }));
    expect(fetcher.mock.calls.some(([url, options]) => options?.method === "POST" && url !== "/api/examples/information/demo")).toBe(false);
  });

  it("enables only the matching bank demo for a bank world", async () => {
    const { fetcher } = await setup("en", undefined, worlds);
    await fireEvent.click(screen.getByRole('link', { name: 'Demo world' }));
    await screen.findByLabelText('Demo world');
    await fireEvent.update(screen.getByLabelText("Demo world"), "bank-selected");
    await fireEvent.update(screen.getByLabelText('Maximum simulation rounds'), '20');
    expect((screen.getByRole("button", { name: "Create leak demo" }) as HTMLButtonElement).disabled).toBe(true);
    await fireEvent.click(screen.getByRole("button", { name: "Create bank demo" }));
    await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/examples/bank/demo", expect.objectContaining({
      method: "POST", body: JSON.stringify({ worldId: "bank-selected", maxSteps: 20 }),
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

it.each(['zh', 'en'] as const)('validates integer round limits in both creation forms in %s', async lang => {
  const world: World = { id: 'w', name: 'World', domainCode: 'information', version: 1, entityTypes: [], relationTypes: [], entities: [], relations: [] };
  const { fetcher } = await setup(lang, undefined, [world]);
  const label = lang === 'zh' ? '推演最大轮数' : 'Maximum simulation rounds';
  for (const value of ['0', '21', '1.5', '']) {
    await fireEvent.update(screen.getByLabelText(label), value);
    expect((screen.getByRole('button', { name: lang === 'zh' ? '创建主从 Agent 场景' : 'Create hierarchical scenario' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByRole('alert').textContent).toContain(lang === 'zh' ? '1～20 的整数' : 'integer from 1 to 20');
  }
  await fireEvent.update(screen.getByLabelText(label), '1');
  expect((screen.getByRole('button', { name: lang === 'zh' ? '创建主从 Agent 场景' : 'Create hierarchical scenario' }) as HTMLButtonElement).disabled).toBe(false);
  await fireEvent.click(screen.getByRole('link', { name: lang === 'zh' ? '演示世界' : 'Demo world' }));
  await screen.findByLabelText(lang === 'zh' ? '演示世界' : 'Demo world');
  await fireEvent.update(screen.getByLabelText(lang === 'zh' ? '演示世界' : 'Demo world'), 'w');
  for (const value of ['0', '21', '1.5', '']) {
    await fireEvent.update(screen.getByLabelText(label), value);
    const button = screen.getByRole('button', { name: lang === 'zh' ? '创建泄密演示' : 'Create leak demo' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    await fireEvent.click(button);
  }
  expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
  await fireEvent.update(screen.getByLabelText(label), '1');
  await fireEvent.click(screen.getByRole('button', { name: lang === 'zh' ? '创建泄密演示' : 'Create leak demo' }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith('/api/examples/information/demo', expect.objectContaining({ body: JSON.stringify({ worldId: 'w', maxSteps: 1 }) })));
});

it('keeps six sidebar destinations and limits demo operations to their own page', async () => {
  const { router } = await setup();
  const navigation = screen.getByRole('navigation', { name: 'Workspace navigation' });
  expect(within(navigation).getAllByRole('link')).toHaveLength(7);
  expect(within(navigation).getByRole('link', { name: 'Rules' }).getAttribute('href')).toBe('/rules');
  expect(within(navigation).getByRole('link', { name: 'Scenarios' }).getAttribute('aria-current')).toBe('page');
  expect(screen.queryByLabelText('Demo world')).toBeNull();
  expect(screen.queryByRole('button', { name: 'Create leak demo' })).toBeNull();
  await fireEvent.click(within(navigation).getByRole('link', { name: 'Demo world' }));
  await screen.findByLabelText('Demo world');
  expect(router.currentRoute.value.path).toBe('/demos');
  expect(screen.queryByRole('button', { name: 'Create hierarchical scenario' })).toBeNull();
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

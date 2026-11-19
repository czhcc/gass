import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/vue';
import { createPinia, setActivePinia } from 'pinia';
import Actions from './components/Actions.vue';
import { useWorkspaceStore } from './stores/workspace';
import type { ActionLibraryEntry, World } from './types';
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const definition = { actionType: 'CUSTOM_SCORE', nameZh: '自定义评分', nameEn: 'Custom score', description: '计算模拟评分', parametersSchema: { type: 'object', properties: {}, required: [], additionalProperties: false }, preconditions: [], effect: 'CUSTOM', program: { version: 1, conditions: [], operations: [{ op: 'SET_PROPERTY', arguments: { object: 'target.id', property: "'customScore'", value: '42' } }], parameterOptions: {} } };
const draft: ActionLibraryEntry = { id: 'draft', domainCode: 'bank', ownerType: 'BANK_ACCOUNT', revision: 1, status: 'DRAFT', source: 'MANUAL', modelId: null, definition, version: 1 };
const world: World = { id: 'w', name: '银行世界', domainCode: 'bank', version: 3, entityTypes: ['BANK_ACCOUNT'], relationTypes: [], entities: [{ id: 'a', type: 'BANK_ACCOUNT', name: '账户 A', properties: { balanceMinor: 100 } }], relations: [], ontology: { version: 3, types: [] } };
function setup(entries: ActionLibraryEntry[] = []) {
  const pinia = createPinia(); setActivePinia(pinia);
  const store = useWorkspaceStore(); store.worlds = [world];
  store.models = [{ id: 'mock', name: '模拟模型', baseUrl: 'mock://local', modelName: 'MOCK', keyConfigured: false, apiKeyEnv: null, temperature: 0, maxTokens: 1024, timeoutSeconds: 30, concurrency: 1 }];
  let saved = entries;
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'DELETE') { saved = []; return { ok: true, status: 204 }; }
    if (url.endsWith('/generate')) { saved = [{ ...draft, source: 'MOCK' }]; return { ok: true, json: async () => saved[0] }; }
    if (url.endsWith('/validate')) return { ok: true, json: async () => ({ valid: true }) };
    if (url.endsWith('/publish')) { saved = [{ ...draft, status: 'PUBLISHED', version: 2 }]; return { ok: true, json: async () => saved[0] }; }
    if (url.endsWith('/bind')) return { ok: true, json: async () => ({ ...world, version: 4 }) };
    if (url.endsWith('/preview')) return { ok: true, json: async () => ({ accepted: true, message: 'Preview success', state: { entities: world.entities, relations: [] } }) };
    if (init?.method === 'POST' && url === '/api/action-library') { saved = [draft]; return { ok: true, json: async () => draft }; }
    return { ok: true, json: async () => saved };
  }); vi.stubGlobal('fetch', fetcher);
  render(Actions, { props: { lang: 'zh' }, global: { plugins: [pinia] } });
  return { fetcher, store };
}
it('creates, validates, previews and publishes a manual CEL draft, then explicitly binds its revision', async () => {
  const { fetcher, store } = setup();
  await fireEvent.update(screen.getByLabelText('适用领域'), 'bank');
  await fireEvent.update(screen.getByLabelText('Action 编码'), 'CUSTOM_SCORE');
  await fireEvent.update(screen.getByLabelText('中文名称'), '自定义评分');
  await fireEvent.update(screen.getByLabelText('英文名称'), 'Custom score');
  await fireEvent.update(screen.getByLabelText('用途与执行效果'), '计算模拟评分');
  await fireEvent.click(screen.getByRole('button', { name: '增加执行步骤' }));
  await fireEvent.update(screen.getByLabelText('属性名（CEL） 1'), "'customScore'");
  await fireEvent.update(screen.getByLabelText('属性值（CEL） 1'), '42');
  await fireEvent.click(screen.getByRole('button', { name: '校验定义' }));
  await screen.findByText('定义校验通过');
  await fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  await screen.findByText('草稿已保存');
  await fireEvent.update(screen.getByLabelText('目标世界'), 'w');
  await fireEvent.update(screen.getByLabelText('试算对象'), 'a');
  await fireEvent.click(screen.getByRole('button', { name: '试算 Action' }));
  await screen.findByText('Preview success');
  expect(fetcher.mock.calls.some(([url]) => url.includes('/runs'))).toBe(false);
  await fireEvent.click(screen.getByRole('button', { name: '发布此版本' }));
  await screen.findByText('版本已发布');
  expect(screen.getByLabelText('中文名称').matches(':disabled')).toBe(true);
  await fireEvent.click(screen.getByRole('button', { name: '绑定到世界对象类型' }));
  await waitFor(() => expect(store.worlds[0]?.version).toBe(4));
  expect(fetcher).toHaveBeenCalledWith('/api/action-library/draft/bind', expect.objectContaining({ method: 'POST', body: JSON.stringify({ worldId: 'w', expectedVersion: 3 }) }));
});
it('labels simulated generation and saves it as a draft without publishing or binding', async () => {
  const { fetcher } = setup();
  await fireEvent.update(screen.getByLabelText('适用领域'), 'bank');
  await fireEvent.update(screen.getByLabelText('生成模型'), 'mock');
  await fireEvent.update(screen.getByLabelText('Action 需求描述'), '根据余额计算风险');
  await fireEvent.click(screen.getByRole('button', { name: '生成 Action 草稿' }));
  await screen.findByText('模拟模型生成的是固定模板，请编辑并试算。');
  expect(screen.getAllByText('草稿 · 模拟模型').length).toBeGreaterThan(0);
  expect(fetcher.mock.calls.some(([url]) => url.endsWith('/publish') || url.endsWith('/bind'))).toBe(false);
});
it('confirms deleting a draft in a dialog and sends its edit version only after confirmation', async () => {
  const { fetcher } = setup([draft]);
  await fireEvent.update(screen.getByLabelText('适用领域'), 'bank');
  await screen.findByRole('button', { name: '载入' });
  await fireEvent.click(screen.getByRole('button', { name: '载入' }));
  await fireEvent.click(screen.getByRole('button', { name: '删除草稿' }));
  const dialog = screen.getByRole('dialog');
  expect(within(dialog).getByText('确认删除此草稿？')).toBeTruthy();
  await fireEvent.click(within(dialog).getByRole('button', { name: '取消' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(fetcher.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false);
  await fireEvent.click(screen.getByRole('button', { name: '删除草稿' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '确认删除' }));
  await screen.findByText('已删除');
  expect(fetcher).toHaveBeenCalledWith('/api/action-library/draft?version=1', expect.objectContaining({ method: 'DELETE' }));
});

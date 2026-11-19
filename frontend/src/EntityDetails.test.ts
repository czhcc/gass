import { afterEach, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/vue";
import EntityDetails from "./components/EntityDetails.vue";
import type { ActionCatalog, ActionDefinition } from "./types";
afterEach(cleanup);
const action: ActionDefinition = { actionType: "REVOKE_SHARING", nameZh: "撤销共享", nameEn: "Revoke sharing", description: "Revoke outgoing sharing", parametersSchema: { type: "object", properties: { recipientId: { type: "string", description: "Recipient object" } }, required: ["recipientId"] }, preconditions: ["ENABLED_SHARING_RECIPIENT"], effect: "REVOKE_SHARING_WITH" };
const entity = { id: "a", type: "PERSON", name: "Alice", properties: { reviewed: true } };
it("shows object state and type-owned action contract, ignoring other types", () => {
  render(EntityDetails, { props: { entity, lang: "zh", ontology: { version: 2, types: [{ targetKind: "ENTITY", type: "PERSON", actions: [action] }, { targetKind: "ENTITY", type: "SYSTEM", actions: [{ ...action, actionType: "DISABLE_SYNC" }] }] } } });
  expect(screen.getByText("撤销共享")).toBeTruthy();
  expect(screen.getByText("REVOKE_SHARING")).toBeTruthy();
  expect(screen.getByText('(recipientId)')).toBeTruthy();
  expect(screen.getByText(/ENABLED_SHARING_RECIPIENT/)).toBeTruthy();
  expect(screen.getByText(/REVOKE_SHARING_WITH/)).toBeTruthy();
  expect(screen.queryByText("DISABLE_SYNC")).toBeNull();
  expect(screen.getByText(/reviewed/)).toBeTruthy();
  expect(screen.getByText('共享接收对象')).toBeTruthy();
  expect(screen.getByText('必填')).toBeTruthy();
  expect(screen.getByText('对象 ID（文本）')).toBeTruthy();
  expect(screen.getByText('当前对象与指定接收对象之间，必须存在已启用的对外共享关系，且接收对象对执行 Agent 可见。')).toBeTruthy();
  expect(screen.getByText('由执行引擎关闭当前对象指向指定接收对象的所有已启用共享关系；其他对象及关系保持不变。')).toBeTruthy();
  expect(screen.getByText('技术定义（JSON 与编码）')).toBeTruthy();
  expect(screen.getByText('技术定义（JSON 与编码）').closest('details')?.open).toBe(false);
});
it.each([['sourceId', '传播来源对象'], ['destinationId', '同步目标对象'], ['customValue', 'customValue']])('explains parameter %s and keeps unknown fields visible', (name, label) => {
  render(EntityDetails, { props: { entity, lang: 'zh', ontology: { version: 2, types: [{ targetKind: 'ENTITY', type: 'PERSON', actions: [{ ...action, parametersSchema: { properties: { [name]: { type: 'string', description: '自定义说明' } } } }] }] } } });
  expect(screen.getByText(label)).toBeTruthy();
  expect(screen.getByText('选填')).toBeTruthy();
  if (name === 'customValue') expect(screen.getByText('自定义说明')).toBeTruthy();
});
it.each([
  ['SET_REVIEWED', ['NOT_REVIEWED'], '将当前对象标记为已审查（reviewed = true），审查结论记录在行动理由中。', '当前对象尚未标记为已审查。'],
  ['READ_ONLY', [], '记录对当前对象的只读分析，不修改对象状态或关系；关系可达不代表已经发生泄密。', '无额外状态条件，仍需通过执行权限、对象可见性和参数校验。'],
  ['EXPOSE_ENTITY', ['NOT_EXPOSED', 'ENABLED_DIRECT_SOURCE'], '将当前对象标记为模拟暴露（exposed = true），仅表示模拟结果，不代表真实泄密。', '当前对象尚未标记为模拟暴露。'],
  ['DISABLE_SYNC_TO', ['ENABLED_SYNC_DESTINATION'], '由执行引擎关闭当前系统指向指定目标对象的所有已启用同步关系；其他对象及关系保持不变。', '当前系统与指定目标对象之间，必须存在已启用的对外同步关系，且目标对象对执行 Agent 可见。'],
])('explains %s in Chinese without changing the technical definition', (effect, preconditions, explanation, condition) => {
  render(EntityDetails, { props: { entity, lang: 'zh', ontology: { version: 2, types: [{ targetKind: 'ENTITY', type: 'PERSON', actions: [{ ...action, effect, preconditions, parametersSchema: { properties: {} } }] }] } } });
  expect(screen.getByText(explanation)).toBeTruthy();
  expect(screen.getByText(condition)).toBeTruthy();
  expect(screen.getByText('无需填写参数。')).toBeTruthy();
});
it("filters capabilities by object and never presents live availability as historical state", async () => {
  const catalog: ActionCatalog = { domainCode: "information", version: 1, agents: [{ agentId: "agent", capabilities: [{ targetKind: "ENTITY", targetId: "a", targetName: "Alice", type: "PERSON", action, available: false, unavailableReason: "No visible recipient", sourceIds: [], parameterOptions: { recipientId: ["c"] } }, { targetKind: "ENTITY", targetId: "b", targetName: "Bob", type: "PERSON", action: { ...action, actionType: "OTHER" }, available: true, unavailableReason: "", sourceIds: [] }] }] };
  const view = render(EntityDetails, { props: { entity, lang: "en", catalog, live: true } });
  expect(screen.getByText(/No visible recipient/)).toBeTruthy();
  expect(screen.queryByText("OTHER")).toBeNull();
  await view.rerender({ live: false });
  expect(screen.queryByText(/No visible recipient/)).toBeNull();
  expect(screen.getByText(/Availability is not evaluated for historical playback/)).toBeTruthy();
});

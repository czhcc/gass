import { afterEach, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import ObjectActions from "./components/ObjectActions.vue";
import type { ActionCatalog } from "./types";
afterEach(cleanup);

const action = (actionType: string, nameZh: string, nameEn: string) => {
  const field = actionType === "SIMULATE_PROPAGATION" ? "sourceId" : actionType === "REVOKE_SHARING" ? "recipientId" : actionType === "DISABLE_SYNC" ? "destinationId" : "";
  return { actionType, nameZh, nameEn, description: "Type-owned action", parametersSchema: { type: "object", properties: field ? { [field]: { type: "string" } } : {}, additionalProperties: false }, preconditions: [], effect: "SET_REVIEWED" };
};
const catalog: ActionCatalog = { domainCode: "information", version: 3, agents: [{ agentId: "response", capabilities: [
  { targetKind: "ENTITY", targetId: "system_s", targetName: "系统 S", type: "SYSTEM",
    action: action("DISABLE_SYNC", "禁用同步", "Disable sync"), available: false, unavailableReason: "No visible destination with enabled outgoing synchronization", sourceIds: [], parameterOptions: { destinationId: [] } },
  { targetKind: "ENTITY", targetId: "document_f", targetName: "文件 F", type: "DOCUMENT",
    action: action("MARK_REVIEWED", "标记审查", "Mark reviewed"), available: true, unavailableReason: "", sourceIds: [] },
  { targetKind: "ENTITY", targetId: "person_a", targetName: "人员 A", type: "PERSON",
    action: action("SIMULATE_PROPAGATION", "模拟传播", "Simulate propagation"), available: true, unavailableReason: "", sourceIds: ["document_f"], parameterOptions: { sourceId: ["document_f"] } },
] }] };
const agents = [{ id: "response", name: "Response", goal: "Contain", modelId: "m", allowedActions: ["DISABLE_SYNC", "MARK_REVIEWED", "SIMULATE_PROPAGATION", "END"] }];

it('supports bank scalar parameters and typed custom values instead of treating every field as an object selector', async () => {
  const scalar = { ...action('TRANSFER', '转账', 'Transfer'), parametersSchema: { type: 'object', properties: {
    sourceId: { type: 'string', description: 'Source account' }, amount: { type: 'string', description: 'Amount' }, currency: { type: 'string', description: 'Currency' },
    count: { type: 'integer', description: 'Count' }, confirm: { type: 'boolean', description: 'Confirm' }, note: { type: 'string', description: 'Note' },
  }, required: ['sourceId', 'amount', 'currency', 'count', 'confirm'], additionalProperties: false } };
  const view = render(ObjectActions, { props: { catalog: { domainCode: 'bank', version: 2, agents: [{ agentId: 'response', capabilities: [{ targetKind: 'ENTITY', targetId: 'b', targetName: 'B', type: 'BANK_ACCOUNT', action: scalar, available: true, unavailableReason: '', sourceIds: [], parameterOptions: { sourceId: ['a'] } }] }] }, agents, busy: false, lang: 'en' } });
  await fireEvent.update(screen.getByLabelText('Amount'), '1.25');
  await fireEvent.update(screen.getByLabelText('Currency'), 'CNY');
  await fireEvent.update(screen.getByLabelText('Count'), '3');
  await fireEvent.update(screen.getByLabelText('Confirm'), 'true');
  await fireEvent.click(screen.getByRole('button', { name: 'Execute action' }));
  expect(view.emitted().submit?.[0]).toEqual([expect.objectContaining({ decision: expect.objectContaining({ parameters: { sourceId: 'a', amount: '1.25', currency: 'CNY', count: 3, confirm: true } }) })]);
});

it.each([ ["REVOKE_SHARING", "person_a", "recipientId", "person_c", "Sharing recipient"],
  ["DISABLE_SYNC", "system_s", "destinationId", "system_t", "Sync destination"] ] as const)(
  "submits %s on its owner object with a selected related object", async (id, owner, parameter, related, label) => {
    const capability = { targetKind: "ENTITY", targetId: owner, targetName: owner, type: owner === "person_a" ? "PERSON" : "SYSTEM",
      action: action(id, id, id), available: true, unavailableReason: "", sourceIds: [], parameterOptions: { [parameter]: [related] } };
    const view = render(ObjectActions, { props: { catalog: { ...catalog, agents: [{ agentId: "response", capabilities: [capability] }] }, agents, busy: false, lang: "en" } });
    expect(screen.queryByRole("option", { name: /RELATION/ })).toBeNull();
    await fireEvent.update(screen.getByLabelText(label), related);
    await fireEvent.click(screen.getByRole("button", { name: "Execute action" }));
    expect(view.emitted().submit?.[0]).toEqual([expect.objectContaining({ decision: expect.objectContaining({
      actionType: id, targetKind: "ENTITY", targetId: owner, parameters: { [parameter]: related },
    }) })]);
    await view.rerender({ catalog: { ...catalog, version: 4, agents: [{ agentId: "response", capabilities: [{ ...capability, available: false, parameterOptions: { [parameter]: [] } }] }] } });
    expect((screen.getByRole("button", { name: "Execute action" }) as HTMLButtonElement).disabled).toBe(true);
  });

it.each([ ["en", "Target object", "Object action", "Execute action"], ["zh", "目标对象", "对象行动", "执行行动"] ] as const)(
  "submits typed object actions with engine version and empty parameters in %s", async (lang, target, operation, submit) => {
    const view = render(ObjectActions, { props: { catalog, agents, busy: false, lang } });
    await fireEvent.update(screen.getByLabelText(target), "ENTITY:document_f");
    expect((screen.getByLabelText(operation) as HTMLSelectElement).value).toBe("MARK_REVIEWED");
    await fireEvent.click(screen.getByRole("button", { name: submit }));
    expect(view.emitted().submit?.[0]).toEqual([{ agentId: "response", expectedVersion: 3,
      decision: { actionType: "MARK_REVIEWED", targetId: "document_f", targetKind: "ENTITY", parameters: {}, reason: "User-requested simulated action" } }]);
    await fireEvent.update(screen.getByLabelText(target), "ENTITY:system_s");
    expect((screen.getByRole("button", { name: submit }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(lang === "zh" ? "当前没有可见且启用同步的目标对象。" : "No visible destination with enabled outgoing synchronization")).toBeTruthy();
  });

it("uses engine-provided propagation sources and rechecks availability after state updates", async () => {
  const view = render(ObjectActions, { props: { catalog, agents, busy: false, lang: "en" } });
  await fireEvent.update(screen.getByLabelText("Target object"), "ENTITY:person_a");
  await fireEvent.update(screen.getByLabelText("Propagation source"), "document_f");
  await fireEvent.click(screen.getByRole("button", { name: "Execute action" }));
  expect(view.emitted().submit?.[0]).toEqual([expect.objectContaining({ decision: expect.objectContaining({ parameters: { sourceId: "document_f" } }) })]);
  await view.rerender({ catalog: { ...catalog, version: 4, agents: [{ agentId: "response", capabilities: [] }] } });
  await waitFor(() => expect((screen.getByRole("button", { name: "Execute action" }) as HTMLButtonElement).disabled).toBe(true));
});

it("disables object decisions during requests and hides lifecycle control after the agent ends", async () => {
  const view = render(ObjectActions, { props: { catalog, agents, busy: true, lang: "en" } });
  await fireEvent.click(screen.getByRole("button", { name: "Execute action" }));
  expect(view.emitted().submit).toBeUndefined();
  await view.rerender({ busy: false, catalog: { ...catalog, agents: [{ agentId: "response", capabilities: [], ended: true }] } });
  expect(screen.queryByRole("button", { name: "End this agent's task" })).toBeNull();
});

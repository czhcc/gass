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
  expect(screen.getByText(/recipientId/)).toBeTruthy();
  expect(screen.getByText(/ENABLED_SHARING_RECIPIENT/)).toBeTruthy();
  expect(screen.getByText(/REVOKE_SHARING_WITH/)).toBeTruthy();
  expect(screen.queryByText("DISABLE_SYNC")).toBeNull();
  expect(screen.getByText(/reviewed/)).toBeTruthy();
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

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/vue";
import { createPinia, setActivePinia } from "pinia";
import Worlds from "./components/Worlds.vue";
import { useWorkspaceStore } from "./stores/workspace";
import type { World } from "./types";

vi.mock("./components/Graph.vue", () => ({ __esModule: true, default: { template: "<div>Graph preview</div>" } }));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const initial: World = {
  id: "demo-information", name: "Leak initial", domainCode: "information", version: 1,
  entityTypes: ["DOCUMENT"], relationTypes: ["SHARED_WITH"],
  entities: [{ id: "doc", type: "DOCUMENT", name: "Document", properties: {} }], relations: [],
};
const custom: World = { ...initial, id: "custom", name: "Custom world" };

function setup(lang: "en" | "zh" = "en") {
  const pinia = createPinia();
  setActivePinia(pinia);
  const store = useWorkspaceStore();
  store.worlds = [structuredClone(initial), { ...structuredClone(initial), id: "demo-bank", domainCode: "bank", name: "Bank initial" }, structuredClone(custom)];
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  render(Worlds, { props: { lang }, global: { plugins: [pinia] } });
  return { store, fetcher };
}

describe("world maintenance", () => {
  it("shows type-owned action contracts and preserves them when editing graph data", async () => {
    const { store, fetcher } = setup();
    const ontology = { version: 1, types: [{ targetKind: "ENTITY", type: "DOCUMENT", actions: [{
      actionType: "MARK_REVIEWED", nameZh: "标记审查", nameEn: "Mark reviewed", description: "Review a document",
      parametersSchema: { type: "object", properties: {}, additionalProperties: false }, preconditions: ["NOT_REVIEWED"], effect: "SET_REVIEWED",
    }] }] };
    store.worlds[0]!.ontology = ontology;
    fetcher.mockResolvedValue({ ok: true, json: async () => ({ ...initial, ontology, version: 2 }) });
    await fireEvent.click(screen.getByRole("button", { name: "Leak initial" }));
    expect(screen.getByRole("heading", { name: "Type action definitions" })).toBeTruthy();
    expect(screen.getByText("Mark reviewed · MARK_REVIEWED")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Edit world" }));
    await fireEvent.click(screen.getByRole("button", { name: "Save world" }));
    await waitFor(() => expect(fetcher).toHaveBeenCalled());
    expect(JSON.parse(fetcher.mock.calls[0][1].body).ontology).toEqual(ontology);
  });

  it("filters worlds, views their graph and protects the initial worlds", async () => {
    setup();
    await fireEvent.click(screen.getByRole("button", { name: "Leak initial" }));
    await screen.findByText("Graph preview");
    expect((screen.getByRole("button", { name: "Delete world" }) as HTMLButtonElement).disabled).toBe(true);
    await fireEvent.update(screen.getByLabelText("Search worlds"), "Custom");
    expect(screen.queryByRole("button", { name: "Leak initial" })).toBeNull();
    expect(screen.getByRole("button", { name: "Custom world" })).toBeTruthy();
  });

  it("updates a world in place with its current version", async () => {
    const { fetcher, store } = setup();
    fetcher.mockResolvedValue({ ok: true, json: async () => ({ ...custom, name: "Edited", version: 2 }) });
    await fireEvent.click(screen.getByRole("button", { name: "Custom world" }));
    await fireEvent.click(screen.getByRole("button", { name: "Edit world" }));
    await fireEvent.update(screen.getByLabelText("World name"), "Edited");
    await fireEvent.click(screen.getByRole("button", { name: "Save world" }));
    await waitFor(() => expect(store.worlds.find(w => w.id === "custom")?.name).toBe("Edited"));
    expect(fetcher).toHaveBeenCalledWith("/api/worlds/custom", expect.objectContaining({ method: "PUT" }));
    const body = JSON.parse(fetcher.mock.calls[0][1].body);
    expect(body.version).toBe(1);
    expect(body.entities[0].id).toBe("doc");
  });

  it("keeps input on update failure and validates JSON before sending", async () => {
    const { fetcher } = setup();
    fetcher.mockResolvedValue({ ok: false, status: 409, json: async () => ({ message: "World changed" }) });
    await fireEvent.click(screen.getByRole("button", { name: "Custom world" }));
    await fireEvent.click(screen.getByRole("button", { name: "Edit world" }));
    await fireEvent.update(screen.getByLabelText("World name"), "Unsaved");
    await fireEvent.click(screen.getByRole("button", { name: "Save world" }));
    await screen.findByRole("alert");
    expect((screen.getByLabelText("World name") as HTMLInputElement).value).toBe("Unsaved");
    fetcher.mockClear();
    await fireEvent.update(screen.getByLabelText("Entities JSON"), "invalid");
    await fireEvent.click(screen.getByRole("button", { name: "Save world" }));
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("creates a world from editable initial data and copies worlds independently", async () => {
    const { fetcher, store } = setup();
    fetcher.mockResolvedValueOnce({ ok: true, json: async () => ({ ...custom, id: "new", name: "New world" }) });
    await fireEvent.click(screen.getByRole("button", { name: "Create world" }));
    await fireEvent.update(screen.getByLabelText("World name"), "New world");
    await fireEvent.click(screen.getByRole("button", { name: "Save world" }));
    await waitFor(() => expect(store.worlds.some(w => w.id === "new")).toBe(true));
    expect(fetcher).toHaveBeenCalledWith("/api/worlds", expect.objectContaining({ method: "POST" }));
    fetcher.mockResolvedValueOnce({ ok: true, json: async () => ({ ...custom, id: "copy", name: "New world copy" }) });
    await fireEvent.click(screen.getByRole("button", { name: "Copy world" }));
    await waitFor(() => expect(store.worlds.some(w => w.id === "copy")).toBe(true));
    expect(fetcher).toHaveBeenCalledWith("/api/worlds/new/copy", expect.objectContaining({ method: "POST" }));
  });

  it("confirms deletion in a popup and retains the world when referenced", async () => {
    const { fetcher, store } = setup();
    fetcher.mockResolvedValueOnce({ ok: false, status: 409, json: async () => ({ message: "World is used by a scenario" }) });
    await fireEvent.click(screen.getByRole("button", { name: "Custom world" }));
    await fireEvent.click(screen.getByRole("button", { name: "Delete world" }));
    const dialog = await screen.findByRole("dialog", { name: "Delete world" });
    await fireEvent.click(within(dialog).getByRole("button", { name: "Confirm deletion" }));
    await waitFor(() => expect(within(dialog).getByRole("alert").textContent).toContain("used by a scenario"));
    expect(store.worlds.some(w => w.id === "custom")).toBe(true);
    fetcher.mockResolvedValueOnce({ ok: true, status: 204 });
    await fireEvent.click(within(dialog).getByRole("button", { name: "Confirm deletion" }));
    await waitFor(() => expect(store.worlds.some(w => w.id === "custom")).toBe(false));
  });

  it("imports JSON into the editor for review before creating a new world", async () => {
    const { fetcher } = setup("zh");
    await fireEvent.click(screen.getByRole("button", { name: "导入 JSON" }));
    await fireEvent.update(screen.getByLabelText("世界定义 JSON"), JSON.stringify(custom));
    await fireEvent.click(screen.getByRole("button", { name: "载入编辑器" }));
    await waitFor(() => expect((screen.getByLabelText("世界名称") as HTMLInputElement).value).toBe("Custom world"));
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("exports a selected world as a reusable JSON download", async () => {
    const { fetcher } = setup();
    const createObjectURL = vi.fn(() => "blob:world-export");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", { createObjectURL, revokeObjectURL });
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    try {
      fetcher.mockResolvedValueOnce({ ok: true, json: async () => custom });
      await fireEvent.click(screen.getByRole("button", { name: "Custom world" }));
      await fireEvent.click(screen.getByRole("button", { name: "Export JSON" }));
      await waitFor(() => expect(click).toHaveBeenCalledOnce());
      expect(fetcher).toHaveBeenCalledWith("/api/worlds/custom/export", expect.anything());
      expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
      expect(revokeObjectURL).toHaveBeenCalledWith("blob:world-export");
    } finally { click.mockRestore(); }
  });
});

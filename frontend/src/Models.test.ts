import { describe, it, expect, vi, afterEach } from "vitest";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  cleanup,
  within,
} from "@testing-library/vue";
import Models from "./components/Models.vue";
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
describe("model configuration", () => {
  it("saves settings, clears secret input, and tests the saved model", async () => {
    const model = {
      id: "m1",
      name: "Local",
      modelName: "qwen",
      baseUrl: "http://localhost:8000/v1",
      keyConfigured: true,
      apiKeyEnv: "",
    };
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [] })
      .mockResolvedValueOnce({ ok: true, json: async () => model })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ ok: true }) });
    vi.stubGlobal("fetch", fetcher);
    render(Models, { props: { lang: "en" } });
    await fireEvent.update(screen.getByLabelText("Name"), "Local");
    await fireEvent.update(
      screen.getByLabelText("Base URL"),
      "http://localhost:8000/v1",
    );
    await fireEvent.update(screen.getByLabelText("Model name"), "qwen");
    await fireEvent.update(screen.getByLabelText("API Key"), "test-secret");
    await fireEvent.click(screen.getByRole("button", { name: "Save model" }));
    await waitFor(() =>
      expect((screen.getByLabelText("API Key") as HTMLInputElement).value).toBe(
        "",
      ),
    );
    await fireEvent.click(
      await screen.findByRole("button", { name: "Test connection" }),
    );
    await screen.findByText("Connection successful");
    expect(JSON.parse(fetcher.mock.calls[1][1].body).apiKey).toBe(
      "test-secret",
    );
    expect(document.body.textContent).not.toContain("test-secret");
  });
});
describe("simulated model mode", () => {
  it("creates an explicitly labelled local model without credentials", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce({ ok: true, json: async () => [] })
        .mockResolvedValueOnce({
          ok: true,
          json: async () => ({
            id: "mock",
            name: "Simulated model",
            modelName: "SIMULATED_MODEL",
            baseUrl: "mock://local",
            keyConfigured: false,
          }),
        }),
    );
    render(Models, { props: { lang: "en" } });
    await fireEvent.click(
      screen.getByRole("button", { name: "Use simulated model" }),
    );
    await screen.findByText("SIMULATED · no external LLM");
    expect(document.body.textContent).not.toContain("local-no-key");
  });
});

describe("saved models", () => {
  const saved = {
    id: "existing",
    name: "Existing provider",
    baseUrl: "https://example.com/v1",
    modelName: "qwen",
    keyConfigured: true,
    apiKeyEnv: "",
    temperature: 0.6,
    maxTokens: 768,
    timeoutSeconds: 20,
    concurrency: 2,
  };
  it("shows saved models below mock controls and edits in place without exposing a secret", async () => {
    const updated = { ...saved, name: "Renamed provider" };
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [saved] })
      .mockResolvedValueOnce({ ok: true, json: async () => updated });
    vi.stubGlobal("fetch", fetcher);
    render(Models, { props: { lang: "en" } });
    const card = await screen.findByRole("article", { name: saved.name });
    expect(screen.getByRole("heading", { name: "Saved models" })).toBeTruthy();
    expect(
      within(card).getByRole("button", { name: "Test connection" }),
    ).toBeTruthy();
    await fireEvent.click(within(card).getByRole("button", { name: "Edit" }));
    expect((screen.getByLabelText("Name") as HTMLInputElement).value).toBe(
      saved.name,
    );
    expect(
      (screen.getByLabelText("Temperature") as HTMLInputElement).value,
    ).toBe("0.6");
    expect((screen.getByLabelText("API Key") as HTMLInputElement).value).toBe(
      "",
    );
    await screen.findByText(
      "Leave API Key blank to retain the saved key. Enter an environment variable to switch credential sources.",
    );
    await fireEvent.update(screen.getByLabelText("Name"), updated.name);
    await fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await screen.findByRole("article", { name: updated.name });
    expect(fetcher.mock.calls[1][0]).toBe("/api/models/existing");
    expect(fetcher.mock.calls[1][1].method).toBe("PUT");
    expect(JSON.parse(fetcher.mock.calls[1][1].body).apiKey).toBe("");
    expect(screen.getAllByRole("article")).toHaveLength(1);
  });
  it("does not lose a newly saved model when the initial list request completes late", async () => {
    let resolveList!: (response: unknown) => void;
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockReturnValueOnce(
          new Promise((resolve) => {
            resolveList = resolve;
          }),
        )
        .mockResolvedValueOnce({ ok: true, json: async () => saved }),
    );
    render(Models, { props: { lang: "en" } });
    await fireEvent.update(screen.getByLabelText("Name"), saved.name);
    await fireEvent.update(screen.getByLabelText("Base URL"), saved.baseUrl);
    await fireEvent.update(
      screen.getByLabelText("Model name"),
      saved.modelName,
    );
    await fireEvent.click(screen.getByRole("button", { name: "Save model" }));
    await screen.findByRole("article", { name: saved.name });
    resolveList({ ok: true, json: async () => [] });
    await waitFor(() =>
      expect(screen.queryByText("Loading saved models…")).toBeNull(),
    );
    await waitFor(() =>
      expect(screen.getByRole("article", { name: saved.name })).toBeTruthy(),
    );
  });
  it("keeps the editor and original saved entry when an update fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce({ ok: true, json: async () => [saved] })
        .mockResolvedValueOnce({
          ok: false,
          status: 400,
          json: async () => ({ message: "Invalid model limits" }),
        }),
    );
    render(Models, { props: { lang: "en" } });
    const card = await screen.findByRole("article", { name: saved.name });
    await fireEvent.click(within(card).getByRole("button", { name: "Edit" }));
    await fireEvent.update(screen.getByLabelText("Name"), "Unsaved change");
    await fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await screen.findByRole("alert");
    expect(screen.getByRole("article", { name: saved.name })).toBeTruthy();
    expect((screen.getByLabelText("Name") as HTMLInputElement).value).toBe(
      "Unsaved change",
    );
  });
});

import { describe, it, expect, vi, afterEach } from "vitest";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  cleanup,
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

import { describe, it, expect, vi, afterEach } from "vitest";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  cleanup,
} from "@testing-library/vue";
import App from "./App.vue";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
function setup() {
  return render(App, {
    global: {
      plugins: [
        createPinia(),
        createRouter({
          history: createMemoryHistory(),
          routes: [{ path: "/:tab?", component: { render: () => null } }],
        }),
      ],
    },
  });
}
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
describe("studio authentication", () => {
  it("switches language and preserves login/logout", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(async (url: string) => ({
        ok: url != "/api/auth/me",
        status: url === "/api/auth/me" ? 401 : 200,
        json: async () => [],
      })),
    );
    setup();
    await screen.findByLabelText("用户名");
    await fireEvent.click(screen.getByText("English"));
    await fireEvent.update(screen.getByLabelText("Username"), "admin");
    await fireEvent.update(screen.getByLabelText("Password"), "123456");
    await fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await screen.findByRole("button", { name: "Sign out" });
    await fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    await screen.findByRole("button", { name: "Sign in" });
  });
  it("shows credential failures", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: false, status: 401 }),
    );
    setup();
    await screen.findByLabelText("用户名");
    await fireEvent.update(screen.getByLabelText("用户名"), "admin");
    await fireEvent.update(screen.getByLabelText("密码"), "bad");
    await fireEvent.click(screen.getByRole("button", { name: "登录" }));
    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain(
        "用户名或密码错误",
      ),
    );
  });
});

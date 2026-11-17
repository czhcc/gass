import { defineStore } from "pinia";
import { ref } from "vue";
import { api } from "../api";
import type { World, Model, Scenario } from "../types";
export const useWorkspaceStore = defineStore("workspace", () => {
  const worlds = ref<World[]>([]),
    models = ref<Model[]>([]),
    scenarios = ref<Scenario[]>([]);
  async function reload() {
    [worlds.value, models.value, scenarios.value] = await Promise.all([
      api<World[]>("/worlds"),
      api<Model[]>("/models"),
      api<Scenario[]>("/scenarios"),
    ]);
  }
  return { worlds, models, scenarios, reload };
});

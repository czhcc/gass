<script setup lang="ts">
import { ref, onMounted, computed, nextTick, watch } from "vue";
import { api } from "../api";
import type { Lang, Scenario } from "../types";
import Models from "./Models.vue";
import Worlds from "./Worlds.vue";
import { defineAsyncComponent } from "vue";
import { useRoute, useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { useWorkspaceStore } from "../stores/workspace";
const RunStudio = defineAsyncComponent(() => import("./RunStudio.vue"));
const route = useRoute(),
  router = useRouter();
const workspace = useWorkspaceStore();
const { worlds, models, scenarios } = storeToRefs(workspace);
const props = defineProps<{ lang: Lang }>();
const tab = computed(() => String(route.params.tab || "worlds"));
const error = ref(""),
  busy = ref(false);
const scenarioForm = ref({ name: "", worldId: "", modelId: "" });
const demoWorldId = ref("");
const demoWorld = computed(() => worlds.value.find((world) => world.id === demoWorldId.value));
watch(() => worlds.value.map(world => world.id), ids => {
  if (!ids.includes(demoWorldId.value)) demoWorldId.value = "";
  if (!ids.includes(scenarioForm.value.worldId)) scenarioForm.value.worldId = "";
});
const createdRun = ref(""),
  agentsJson = ref("");
const confirmingDeletion = ref<string | null>(null),
  scenarioMessage = ref(""),
  deletionError = ref("");
const deleteDialog = ref<HTMLDivElement>(),
  cancelDeletionButton = ref<HTMLButtonElement>(),
  scenarioList = ref<HTMLDivElement>();
let deletionTrigger: HTMLElement | null = null;
const t = computed(() =>
  props.lang === "zh"
    ? {
        studio: "推演与回放",
        bankDemo: "创建银行演示",
        leakDemo: "创建泄密演示",
        demoWorld: "演示世界",
        selectWorld: "请选择已有世界",
        demoHelp: "演示使用所选世界和模拟模型创建场景及运行，不会新增世界。",
        noDemoWorld: "请先在“世界与图谱”中导入或创建世界，再选择世界创建演示。",
        leakDomain: "泄密",
        bankDomain: "银行",
        worlds: "世界与图谱",
        models: "模型配置",
        scenarios: "场景配置",
        note: "推演生成模拟状态，不代表真实世界事实。可选择无需密钥的模拟模型，或配置真实模型。",
        scenario: "创建场景",
        name: "场景名称",
        world: "世界",
        model: "模型",
        create: "创建双 Agent 场景",
        run: "创建暂停运行",
        delete: "删除",
        confirmDelete: "确认删除",
        cancel: "取消",
        deleteNotice: "确认删除此场景？已有运行记录和推演结果将保留。",
        deleted: "场景已删除",
        saved: "已创建运行；可使用 REST 单步验收",
        agentsJson: "自定义 Agent JSON（可选）",
        agents:
          "调查 Agent 和响应 Agent 将共享所选世界可见范围，使用不同目标与操作权限。",
      }
    : {
        studio: "Simulation & replay",
        bankDemo: "Create bank demo",
        leakDemo: "Create leak demo",
        demoWorld: "Demo world",
        selectWorld: "Select an existing world",
        demoHelp: "Demos create a scenario and run using the selected world and mock model, without adding a world.",
        noDemoWorld: "Import or create a world in Worlds & graph, then select it to create a demo.",
        leakDomain: "Information leak",
        bankDomain: "Bank",
        worlds: "Worlds & graph",
        models: "Models",
        scenarios: "Scenarios",
        note: "Simulation is not real-world fact. Use the credential-free mock model or configure a real model.",
        scenario: "Create scenario",
        name: "Scenario name",
        world: "World",
        model: "Model",
        create: "Create two-agent scenario",
        run: "Create paused run",
        delete: "Delete",
        confirmDelete: "Confirm deletion",
        cancel: "Cancel",
        deleteNotice: "Delete this scenario? Existing runs and simulation results will be retained.",
        deleted: "Scenario deleted",
        saved: "Run created; use REST for single-step acceptance",
        agentsJson: "Custom Agent JSON (optional)",
        agents:
          "Investigator and response agents share this world visibility, with different goals and action permissions.",
      },
);
async function load() {
  try {
    await workspace.reload();
  } catch (e) {
    error.value = String(e);
  }
}
onMounted(load);
async function createScenario() {
  busy.value = true;
  error.value = "";
  try {
    const w = worlds.value.find((w) => w.id === scenarioForm.value.worldId)!;
    const visible = w.entities.map((e) => e.id);
    const s = await api<Scenario>("/scenarios", "POST", {
      name: scenarioForm.value.name,
      worldId: w.id,
      maxSteps: 5,
      maxEvents: 500,
      agents: agentsJson.value.trim()
        ? JSON.parse(agentsJson.value)
        : w.domainCode === "bank"
          ? [
              {
                id: "bank",
                name: "Bank Agent",
                goal: "Process simulated transfer",
                modelId: scenarioForm.value.modelId,
                allowedActions: ["TRANSFER", "ANALYZE_ACCOUNT", "END"],
                visibleEntityIds: visible,
                maxDecisions: 10,
              },
              {
                id: "risk",
                name: "Risk Agent",
                goal: "Flag simulated transfer risk",
                modelId: scenarioForm.value.modelId,
                allowedActions: ["FLAG_RISK", "ANALYZE_ACCOUNT", "END"],
                visibleEntityIds: visible,
                maxDecisions: 10,
              },
            ]
          : [
              {
                id: "investigation",
                name: "Investigation Agent",
                goal: "Investigate potential paths and record simulated reviews. Connectivity is not proof of propagation.",
                modelId: scenarioForm.value.modelId,
                allowedActions: ["ANALYZE_RELATIONS", "MARK_REVIEWED", "END"],
                visibleEntityIds: visible,
                maxDecisions: 10,
              },
              {
                id: "response",
                name: "Response Agent",
                goal: "Reduce simulated propagation risk using currently enabled channels. Review the updated state each round.",
                modelId: scenarioForm.value.modelId,
                allowedActions: [
                  "DISABLE_SYNC",
                  "REVOKE_SHARING",
                  "MARK_REVIEWED",
                  "END",
                ],
                visibleEntityIds: visible,
                maxDecisions: 10,
              },
            ],
    });
    scenarios.value.push(s);
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function openDeletion(id: string, event: MouseEvent) {
  deletionTrigger = event.currentTarget as HTMLElement;
  confirmingDeletion.value = id;
  scenarioMessage.value = "";
  deletionError.value = "";
  await nextTick();
  cancelDeletionButton.value?.focus();
}
async function cancelDeletion() {
  if (busy.value) return;
  confirmingDeletion.value = null;
  deletionError.value = "";
  await nextTick();
  deletionTrigger?.focus();
}
function deletionKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault();
    void cancelDeletion();
  } else if (event.key === "Tab") {
    const buttons = Array.from(
      deleteDialog.value?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)") ?? [],
    );
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
    event.preventDefault();
    if (buttons.length) {
      buttons[(index + (event.shiftKey ? -1 : 1) + buttons.length) % buttons.length]?.focus();
    }
  }
}
async function deleteScenario(id: string) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  deletionError.value = "";
  scenarioMessage.value = "";
  try {
    await api<void>("/scenarios/" + id, "DELETE");
    scenarios.value = scenarios.value.filter((scenario) => scenario.id !== id);
    confirmingDeletion.value = null;
    scenarioMessage.value = t.value.deleted;
    await nextTick();
    scenarioList.value?.focus();
  } catch (e) {
    deletionError.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function run(id: string) {
  busy.value = true;
  try {
    const result = await api<{ id: string }>(
      "/scenarios/" + id + "/runs",
      "POST",
    );
    createdRun.value = result.id;
    const scenario = scenarios.value.find((s) => s.id === id);
    const world = worlds.value.find((w) => w.id === scenario?.worldId);
    const document = world?.entities.find((e) => e.type === "DOCUMENT");
    if (document)
      await api(
        "/runs/" + result.id + "/inject",
        "POST",
        {
          eventType: "POTENTIAL_LEAK",
          targetId: document.id,
          simulationTime: 0,
          parameters: { confidence: 0.8 },
        },
        { "Idempotency-Key": crypto.randomUUID() },
      );
    if (world?.domainCode === "bank")
      await api(
        "/runs/" + result.id + "/inject",
        "POST",
        {
          eventType: "BANK_TRANSFER",
          targetId: "account_b",
          simulationTime: 0,
          parameters: {
            sourceId: "account_a",
            amount: "5000000.00",
            currency: "CNY",
          },
        },
        { "Idempotency-Key": crypto.randomUUID() },
      );
    await router.push("/studio?run=" + result.id);
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function demo(domain: string) {
  if (busy.value || !demoWorld.value || demoWorld.value.domainCode !== domain) return;
  busy.value = true;
  error.value = "";
  try {
    const run = await api<{ id: string }>(
      "/examples/" + domain + "/demo",
      "POST",
      { worldId: demoWorldId.value },
    );
    await workspace.reload();
    await router.push("/studio?run=" + run.id);
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="mx-auto max-w-7xl p-5" :inert="confirmingDeletion !== null ? true : undefined">
    <nav class="mb-6 flex flex-wrap gap-3 border-b border-slate-200 pb-4">
      <button
        v-for="item in ['worlds', 'models', 'scenarios', 'studio']"
        :key="item"
        :class="tab === item ? 'primary' : 'secondary'"
        @click="
          router.push('/' + item);
          load();
        "
      >
        {{ t[item as "worlds" | "models" | "scenarios" | "studio"] }}
      </button>
    </nav>
    <p class="mb-5 text-sm text-slate-500">{{ t.note }}</p>
    <section class="panel mb-5 space-y-3">
      <label>
        {{ t.demoWorld }}
        <select v-model="demoWorldId" :disabled="busy">
          <option disabled value="">{{ t.selectWorld }}</option>
          <option v-for="world in worlds" :key="world.id" :value="world.id">
            {{ world.name }} · {{ world.domainCode === 'bank' ? t.bankDomain : t.leakDomain }} · {{ world.id.slice(0, 8) }}
          </option>
        </select>
      </label>
      <p class="text-sm text-slate-500">{{ worlds.length ? t.demoHelp : t.noDemoWorld }}</p>
      <div class="flex flex-wrap gap-3">
      <button class="secondary" :disabled="busy || demoWorld?.domainCode !== 'information'" @click="demo('information')">
        {{ t.leakDemo }}</button
      ><button class="secondary" :disabled="busy || demoWorld?.domainCode !== 'bank'" @click="demo('bank')">
        {{ t.bankDemo }}
      </button>
      </div>
    </section>
    <p v-if="error" role="alert">{{ error }}</p>
    <RunStudio
      v-if="tab === 'studio'"
      :key="String(route.query.run ?? 'studio')"
      :lang="lang"
      :initial-id="route.query.run ? String(route.query.run) : undefined"
    /><Models v-else-if="tab === 'models'" :lang="lang" />
    <Worlds v-else-if="tab === 'worlds'" :lang="lang" />
    <section v-else class="grid gap-5 lg:grid-cols-2">
      <form class="panel grid gap-4" @submit.prevent="createScenario">
        <h2 class="text-xl font-semibold">{{ t.scenario }}</h2>
        <label>{{ t.name }}<input v-model="scenarioForm.name" required /></label
        ><label
          >{{ t.world
          }}<select v-model="scenarioForm.worldId" required>
            <option v-for="world in worlds" :key="world.id" :value="world.id">
              {{ world.name }} · {{ world.id.slice(0, 8) }}
            </option>
          </select></label
        ><label
          >{{ t.model
          }}<select v-model="scenarioForm.modelId" required>
            <option v-for="model in models" :key="model.id" :value="model.id">
              {{ model.name }}
            </option>
          </select></label
        >
        <p class="text-xs text-slate-500">{{ t.agents }}</p>
        <label
          >{{ t.agentsJson }}<textarea v-model="agentsJson" rows="5" /></label
        ><button class="primary" :disabled="busy">{{ t.create }}</button>
      </form>
      <div ref="scenarioList" class="space-y-4" tabindex="-1">
        <article v-for="scenario in scenarios" :key="scenario.id" class="panel">
          <h3 class="font-semibold">{{ scenario.name }}</h3>
          <button
            class="secondary mt-3"
            :disabled="busy"
            @click="run(scenario.id)"
          >
            {{ t.run }}
          </button>
          <button
            class="secondary mt-3 ml-2 text-red-700"
            type="button"
            :disabled="busy"
            @click="openDeletion(scenario.id, $event)"
          >
            {{ t.delete }}
          </button>
        </article>
        <p v-if="scenarioMessage" role="status">{{ scenarioMessage }}</p>
        <p v-if="createdRun" role="status" class="break-all">
          {{ t.saved }}: {{ createdRun }}
        </p>
      </div>
    </section>
  </div>
  <Teleport to="body">
    <div
      v-if="confirmingDeletion !== null"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-5"
      @click.self="cancelDeletion"
    >
      <div
        ref="deleteDialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="scenario-delete-title"
        aria-describedby="scenario-delete-description"
        class="panel w-full max-w-md"
        @keydown="deletionKeydown"
      >
        <h2 id="scenario-delete-title" class="text-lg font-semibold">
          {{ t.confirmDelete }}
        </h2>
        <p id="scenario-delete-description" class="mt-3 text-sm text-slate-600">
          {{ t.deleteNotice }}
        </p>
        <p v-if="deletionError" role="alert">{{ deletionError }}</p>
        <div class="mt-6 flex justify-end gap-3">
          <button
            class="secondary text-red-700"
            type="button"
            :disabled="busy"
            @click="deleteScenario(confirmingDeletion)"
          >
            {{ t.confirmDelete }}
          </button>
          <button
            ref="cancelDeletionButton"
            class="secondary"
            type="button"
            :disabled="busy"
            @click="cancelDeletion"
          >
            {{ t.cancel }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

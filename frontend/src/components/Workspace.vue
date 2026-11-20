<script setup lang="ts">
import { ref, onMounted, computed, nextTick, watch } from "vue";
import { api } from "../api";
import type { Lang, Scenario, ScenarioProfile } from "../types";
import Models from "./Models.vue";
import Worlds from "./Worlds.vue";
import Actions from "./Actions.vue";
import { defineAsyncComponent } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
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
const scenarioForm = ref({ name: "", worldId: "", modelId: "", maxSteps: 5 });
const demoMaxSteps = ref<number | string>(8);
const validRounds = (value: unknown) => typeof value === 'number' && Number.isInteger(value) && value >= 1 && value <= 20;
const extraPermissions = ref<string[]>([]);
const profiles = ref<ScenarioProfile[]>([]);
const teamJson = ref('');
const selectedProfile = computed(() => profiles.value.find(p => p.domainCode === worlds.value.find(w => w.id === scenarioForm.value.worldId)?.domainCode));
const customActions = computed(() => {
  const known = new Set(['TRANSFER', 'FLAG_RISK', 'ANALYZE_ACCOUNT', 'ASSESS_RISK_CEL', 'MARK_REVIEWED', 'ANALYZE_RELATIONS', 'SIMULATE_PROPAGATION', 'REVOKE_SHARING', 'DISABLE_SYNC', 'END']);
  const w = worlds.value.find(w => w.id === scenarioForm.value.worldId);
  return [...new Map((w?.ontology?.types ?? []).flatMap(t => t.actions).filter(a => !known.has(a.actionType)).map(a => [a.actionType, a])).values()];
});
watch(() => scenarioForm.value.worldId, () => { extraPermissions.value = []; agentsJson.value = ''; teamJson.value = ''; });
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
        actions: "Action 维护",
        scenarios: "场景配置",
        note: "推演生成模拟状态，不代表真实世界事实。可选择无需密钥的模拟模型，或配置真实模型。",
        scenario: "创建场景",
        name: "场景名称",
        world: "世界",
        model: "模型",
        maxSteps: "推演最大轮数",
        roundsHelp: "可设置 1～20 轮；到达指定轮数后由引擎判定效果是否达成，资源预算耗尽时提前结束。",
        roundsInvalid: "推演最大轮数必须是 1～20 的整数。",
        create: "创建主从 Agent 场景",
        run: "创建暂停运行",
        delete: "删除",
        confirmDelete: "确认删除",
        cancel: "取消",
        deleteNotice: "确认删除此场景？已有运行记录和推演结果将保留。",
        deleted: "场景已删除",
        saved: "已创建运行；可使用 REST 单步验收",
        agentsJson: "角色与提示词 JSON（可选，覆盖模板）",
        agents:
          "主 Agent 负责研判和委派；查询分析、对象操作等子 Agent 按需执行。角色、工具权限和效果条件来自场景模板。",
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
        actions: "Action library",
        scenarios: "Scenarios",
        note: "Simulation is not real-world fact. Use the credential-free mock model or configure a real model.",
        scenario: "Create scenario",
        name: "Scenario name",
        world: "World",
        model: "Model",
        maxSteps: "Maximum simulation rounds",
        roundsHelp: "Choose 1–20 rounds. The engine evaluates effects at this horizon; resource limits may stop computation earlier.",
        roundsInvalid: "Maximum simulation rounds must be an integer from 1 to 20.",
        create: "Create hierarchical scenario",
        run: "Create paused run",
        delete: "Delete",
        confirmDelete: "Confirm deletion",
        cancel: "Cancel",
        deleteNotice: "Delete this scenario? Existing runs and simulation results will be retained.",
        deleted: "Scenario deleted",
        saved: "Run created; use REST for single-step acceptance",
        agentsJson: "Roles and prompts JSON (optional template override)",
        agents:
          "A master delegates to query, analysis and Action workers as needed. Roles, tool permissions and outcome conditions come from the scenario template.",
      },
);
async function load() {
  try {
    await Promise.all([workspace.reload(), api<ScenarioProfile[]>('/scenario-profiles').then(value => { profiles.value = value; })]);
  } catch (e) {
    error.value = String(e);
  }
}
onMounted(load);
async function createScenario() {
  if (busy.value || !validRounds(scenarioForm.value.maxSteps)) return;
  busy.value = true;
  error.value = "";
  try {
    const w = worlds.value.find((w) => w.id === scenarioForm.value.worldId)!;
    const visible = w.entities.map((e) => e.id);
    const profile = selectedProfile.value;
    if (!profile) throw new Error(props.lang === 'zh' ? '没有适用于此世界的主从场景模板' : 'No hierarchy profile for this world');
    const configuredAgents = agentsJson.value.trim() ? JSON.parse(agentsJson.value) : profile.agents.map(a => ({
      ...a, modelId: scenarioForm.value.modelId, visibleEntityIds: visible, maxDecisions: 20,
      allowedActions: a.runtime?.role === 'ACTION' && a.id === profile.agents.find(role => role.runtime?.role === 'ACTION')?.id ? [...a.allowedActions, ...extraPermissions.value] : a.allowedActions,
    }));
    const s = await api<Scenario>("/scenarios", "POST", {
      name: scenarioForm.value.name,
      worldId: w.id,
      maxSteps: scenarioForm.value.maxSteps,
      maxEvents: 2000,
      agents: configuredAgents,
      team: teamJson.value.trim() ? JSON.parse(teamJson.value) : profile.team,
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
    if (world) await api("/runs/" + result.id + "/initial-input", "POST");
    await router.push("/studio?run=" + result.id);
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function demo(domain: string) {
  if (busy.value || !validRounds(demoMaxSteps.value) || !demoWorld.value || demoWorld.value.domainCode !== domain) return;
  busy.value = true;
  error.value = "";
  try {
    const run = await api<{ id: string }>(
      "/examples/" + domain + "/demo",
      "POST",
      { worldId: demoWorldId.value, maxSteps: demoMaxSteps.value },
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
  <div class="workspace-layout" :inert="confirmingDeletion !== null ? true : undefined">
    <aside class="workspace-sidebar">
    <nav class="grid gap-2" :aria-label="lang === 'zh' ? '工作台导航' : 'Workspace navigation'">
      <RouterLink
        v-for="item in ['worlds', 'actions', 'models', 'scenarios', 'studio', 'demos'] as const"
        :key="item"
        :to="'/' + item"
        :aria-current="tab === item ? 'page' : undefined"
        class="workspace-menu-item"
        :class="{ 'workspace-menu-active': tab === item }"
        @click="load"
      >
        {{ item === 'demos' ? t.demoWorld : t[item] }}
      </RouterLink>
    </nav>
    </aside>
    <div class="workspace-content">
    <p class="mb-5 text-sm text-slate-500">{{ t.note }}</p>
    <section v-if="tab === 'demos'" class="panel mb-5 space-y-3">
      <h2 class="text-xl font-semibold">{{ t.demoWorld }}</h2>
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
      <label class="block">{{ t.maxSteps }}<input v-model.number="demoMaxSteps" type="number" min="1" max="20" step="1" required :disabled="busy" aria-describedby="demo-rounds-help" :aria-invalid="!validRounds(demoMaxSteps)" /></label>
      <p id="demo-rounds-help" class="text-xs text-slate-500">{{ t.roundsHelp }}</p>
      <p v-if="!validRounds(demoMaxSteps)" role="alert" class="text-sm text-red-700">{{ t.roundsInvalid }}</p>
      <div class="flex flex-wrap gap-3">
      <button class="secondary" :disabled="busy || !validRounds(demoMaxSteps) || demoWorld?.domainCode !== 'information'" @click="demo('information')">
        {{ t.leakDemo }}</button
      ><button class="secondary" :disabled="busy || !validRounds(demoMaxSteps) || demoWorld?.domainCode !== 'bank'" @click="demo('bank')">
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
    <Actions v-else-if="tab === 'actions'" :lang="lang" />
    <Worlds v-else-if="tab === 'worlds'" :lang="lang" />
    <section v-else-if="tab === 'scenarios'" class="grid gap-5 lg:grid-cols-2">
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
        <div v-if="selectedProfile" class="rounded border border-slate-200 bg-slate-50 p-3 text-sm">
          <p class="font-medium">{{ lang === 'zh' ? selectedProfile.nameZh : selectedProfile.nameEn }}</p>
          <ul class="mt-2 list-inside list-disc"><li v-for="agent in selectedProfile.agents" :key="agent.id">{{ agent.name }} · {{ agent.runtime?.role === 'MASTER' ? (lang === 'zh' ? '主 Agent' : 'Master') : agent.runtime?.role === 'QUERY' ? (lang === 'zh' ? '查询分析' : 'Query and analysis') : (lang === 'zh' ? '对象操作' : 'Object Actions') }}</li></ul>
          <button class="mt-2 text-xs text-blue-700 disabled:opacity-50" type="button" :disabled="busy || !scenarioForm.modelId" @click="agentsJson = JSON.stringify(selectedProfile.agents.map(a => ({...a, modelId: scenarioForm.modelId, visibleEntityIds: worlds.find(w => w.id === scenarioForm.worldId)?.entities.map(e => e.id), maxDecisions: 20})), null, 2); teamJson = JSON.stringify(selectedProfile.team, null, 2)">{{ lang === 'zh' ? '载入模板进行编辑' : 'Load template for editing' }}</button>
        </div>
        <label>{{ t.maxSteps }}<input v-model.number="scenarioForm.maxSteps" type="number" min="1" max="20" step="1" required :disabled="busy" aria-describedby="scenario-rounds-help" :aria-invalid="!validRounds(scenarioForm.maxSteps)" /></label>
        <p id="scenario-rounds-help" class="text-xs text-slate-500">{{ t.roundsHelp }}</p>
        <p v-if="!validRounds(scenarioForm.maxSteps)" role="alert" class="text-sm text-red-700">{{ t.roundsInvalid }}</p>
        <fieldset v-if="customActions.length && !agentsJson.trim()" :disabled="busy" class="grid gap-2 rounded border border-slate-200 p-3">
          <legend class="text-sm">{{ lang === 'zh' ? '额外授权给首个对象操作子 Agent 的自定义 Action' : 'Custom Actions for the first Action worker' }}</legend>
          <label v-for="action in customActions" :key="action.actionType" class="flex items-center gap-2 text-sm"><input v-model="extraPermissions" type="checkbox" :value="action.actionType" class="w-auto" />{{ action.actionType }} · {{ lang === 'zh' ? action.nameZh : action.nameEn }}</label>
        </fieldset>
        <label
          >{{ t.agentsJson }}<textarea v-model="agentsJson" rows="5" /></label>
        <label>{{ lang === 'zh' ? '调度与效果条件 JSON（可选，覆盖模板）' : 'Delegation and outcome conditions JSON (optional template override)' }}<textarea v-model="teamJson" rows="5" /></label>
        <button class="primary" :disabled="busy || !validRounds(scenarioForm.maxSteps)">{{ t.create }}</button>
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

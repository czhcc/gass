<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import { api } from "../api";
import type { Lang, World, Model, Scenario } from "../types";
import Models from "./Models.vue";
import { defineAsyncComponent } from "vue";
import { useRoute, useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { useWorkspaceStore } from "../stores/workspace";
const RunStudio = defineAsyncComponent(() => import("./RunStudio.vue"));
const Graph = defineAsyncComponent(() => import("./Graph.vue"));
const route = useRoute(),
  router = useRouter();
const workspace = useWorkspaceStore();
const { worlds, models, scenarios } = storeToRefs(workspace);
const props = defineProps<{ lang: Lang }>();
const tab = computed(() => String(route.params.tab || "worlds"));
const selected = ref<World>();
const error = ref(""),
  busy = ref(false),
  worldInput = ref("");
const scenarioForm = ref({ name: "", worldId: "", modelId: "" });
const createdRun = ref(""),
  agentsJson = ref("");
const t = computed(() =>
  props.lang === "zh"
    ? {
        studio: "推演与回放",
        bankDemo: "创建银行演示",
        leakDemo: "创建泄密演示",
        bankImport: "导入银行示例",
        worlds: "世界与图谱",
        models: "模型配置",
        scenarios: "场景配置",
        import: "导入泄密示例",
        upload: "导入世界 JSON",
        note: "推演生成模拟状态，不代表真实世界事实。可选择无需密钥的模拟模型，或配置真实模型。",
        scenario: "创建场景",
        name: "场景名称",
        world: "世界",
        model: "模型",
        create: "创建双 Agent 场景",
        run: "创建暂停运行",
        empty: "暂无数据",
        json: "世界定义 JSON",
        saved: "已创建运行；可使用 REST 单步验收",
        agentsJson: "自定义 Agent JSON（可选）",
        agents:
          "调查 Agent 和响应 Agent 将共享所选世界可见范围，使用不同目标与操作权限。",
      }
    : {
        studio: "Simulation & replay",
        bankDemo: "Create bank demo",
        leakDemo: "Create leak demo",
        bankImport: "Import bank example",
        worlds: "Worlds & graph",
        models: "Models",
        scenarios: "Scenarios",
        import: "Import leak example",
        upload: "Import world JSON",
        note: "Simulation is not real-world fact. Use the credential-free mock model or configure a real model.",
        scenario: "Create scenario",
        name: "Scenario name",
        world: "World",
        model: "Model",
        create: "Create two-agent scenario",
        run: "Create paused run",
        empty: "No data",
        json: "World definition JSON",
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
async function importWorld(example: boolean, domain = "information-leak") {
  busy.value = true;
  error.value = "";
  try {
    const w = example
      ? await api<World>("/examples/" + domain, "POST")
      : await api<World>("/worlds", "POST", JSON.parse(worldInput.value));
    worlds.value.push(w);
    selected.value = w;
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
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
  busy.value = true;
  error.value = "";
  try {
    const run = await api<{ id: string }>(
      "/examples/" + domain + "/demo",
      "POST",
      {},
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
  <div class="mx-auto max-w-7xl p-5">
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
    <div class="mb-5 flex flex-wrap gap-3">
      <button class="secondary" :disabled="busy" @click="demo('information')">
        {{ t.leakDemo }}</button
      ><button class="secondary" :disabled="busy" @click="demo('bank')">
        {{ t.bankDemo }}
      </button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <RunStudio
      v-if="tab === 'studio'"
      :key="String(route.query.run ?? 'studio')"
      :lang="lang"
      :initial-id="route.query.run ? String(route.query.run) : undefined"
    /><Models v-else-if="tab === 'models'" :lang="lang" />
    <section
      v-else-if="tab === 'worlds'"
      class="grid gap-5 lg:grid-cols-[300px_1fr]"
    >
      <aside class="panel">
        <button
          class="primary w-full"
          :disabled="busy"
          @click="importWorld(true)"
        >
          {{ t.import }}</button
        ><button
          class="secondary mt-3 w-full"
          :disabled="busy"
          @click="importWorld(true, 'bank-transfer')"
        >
          {{ t.bankImport }}
        </button>
        <ul class="my-5 space-y-2">
          <li v-for="world in worlds" :key="world.id">
            <button class="text-left text-blue-700" @click="selected = world">
              {{ world.name }}
            </button>
          </li>
        </ul>
        <form class="grid gap-3" @submit.prevent="importWorld(false)">
          <label
            >{{ t.json
            }}<textarea v-model="worldInput" rows="8" required /></label
          ><button class="secondary" :disabled="busy">{{ t.upload }}</button>
        </form>
      </aside>
      <article class="panel">
        <template v-if="selected"
          ><h2 class="font-semibold">{{ selected.name }}</h2>
          <Graph :world="selected" />
          <details>
            <summary>{{ t.json }}</summary>
            <pre class="max-h-72 overflow-auto text-xs">{{
              JSON.stringify(selected, null, 2)
            }}</pre>
          </details></template
        >
        <p v-else>{{ t.empty }}</p>
      </article>
    </section>
    <section v-else class="grid gap-5 lg:grid-cols-2">
      <form class="panel grid gap-4" @submit.prevent="createScenario">
        <h2 class="text-xl font-semibold">{{ t.scenario }}</h2>
        <label>{{ t.name }}<input v-model="scenarioForm.name" required /></label
        ><label
          >{{ t.world
          }}<select v-model="scenarioForm.worldId" required>
            <option v-for="world in worlds" :key="world.id" :value="world.id">
              {{ world.name }}
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
      <div class="space-y-4">
        <article v-for="scenario in scenarios" :key="scenario.id" class="panel">
          <h3 class="font-semibold">{{ scenario.name }}</h3>
          <button
            class="secondary mt-3"
            :disabled="busy"
            @click="run(scenario.id)"
          >
            {{ t.run }}
          </button>
        </article>
        <p v-if="createdRun" role="status" class="break-all">
          {{ t.saved }}: {{ createdRun }}
        </p>
      </div>
    </section>
  </div>
</template>

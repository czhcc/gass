<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { ActionCapability, ActionCatalog, Agent, Lang, ObjectActionInput } from "../types";
import { agentDisplayName } from '../agentPresentation';

const props = defineProps<{ catalog: ActionCatalog; agents: Agent[]; lang: Lang; busy: boolean; targetId?: string }>();
const emit = defineEmits<{ submit: [input: ObjectActionInput] }>();
const agentId = ref(""), target = ref(""), actionId = ref("");
const parameterValues = ref<Record<string, string>>({});
const t = computed(() => props.lang === "zh" ? {
  title: "对象行动", help: "选择执行行动的对象，再选择关联对象。Agent 只提出决策，执行引擎校验并维护关系与模拟状态。",
  agent: "执行 Agent", target: "目标对象", action: "对象行动", source: "传播来源", recipient: "共享接收对象", destination: "同步目标对象", submit: "执行行动",
  empty: "当前 Agent 没有可见的对象行动。", schema: "参数规范", unavailable: "当前不可执行", end: "结束此 Agent 的任务",
  entity: "对象", relation: "关系", noParameters: "此行动无需参数，执行引擎负责修改状态。",
} : {
  title: "Object actions", help: "Select the owner object and related objects. Agents decide; the engine validates and maintains relationships and simulation state.",
  agent: "Acting agent", target: "Target object", action: "Object action", source: "Propagation source", recipient: "Sharing recipient", destination: "Sync destination", submit: "Execute action",
  empty: "This agent has no visible object actions.", schema: "Parameter schema", unavailable: "Currently unavailable", end: "End this agent's task",
  entity: "Entity", relation: "Relation", noParameters: "No parameters required. The engine applies state changes.",
});
const capabilities = computed(() => (props.catalog.agents.find(a => a.agentId === agentId.value)?.capabilities ?? []).filter(c => c.targetKind === "ENTITY"));
const key = (c: ActionCapability) => c.targetKind + ":" + c.targetId;
const targets = computed(() => [...new Map(capabilities.value.map(c => [key(c), c])).values()]);
const actions = computed(() => capabilities.value.filter(c => key(c) === target.value));
const chosen = computed(() => actions.value.find(c => c.action.actionType === actionId.value));
const parameterNames = computed(() => Object.keys(chosen.value?.action.parametersSchema.properties ?? {}));
const fields = computed(() => (chosen.value?.action.parametersSchema.properties ?? {}) as Record<string, { type?: string; description?: string; enum?: unknown[] }>);
const required = computed(() => (chosen.value?.action.parametersSchema.required ?? parameterNames.value) as string[]);
const parameterOptions = computed(() => chosen.value?.parameterOptions ?? (chosen.value?.action.actionType === "SIMULATE_PROPAGATION" ? { sourceId: chosen.value.sourceIds } : {}));
const canSubmit = computed(() => chosen.value?.available && parameterNames.value.every(name => {
  const value = parameterValues.value[name] ?? '';
  if (!value) return !required.value.includes(name);
  if (parameterOptions.value[name]) return parameterOptions.value[name]!.includes(value);
  if (fields.value[name]?.type === 'integer') return Number.isSafeInteger(Number(value));
  if (fields.value[name]?.type === 'number') return Number.isFinite(Number(value));
  return fields.value[name]?.type !== 'boolean' || ['true', 'false'].includes(value);
}));
function parameterLabel(name: string) {
  return name === "recipientId" ? t.value.recipient : name === "destinationId" ? t.value.destination : name === "sourceId" ? (props.catalog.domainCode === 'bank' ? (props.lang === 'zh' ? '转出账户' : 'Source account') : t.value.source) : fields.value[name]?.description || name;
}
const canEnd = computed(() => !props.catalog.agents.find(a => a.agentId === agentId.value)?.ended
  && props.agents.find(a => a.id === agentId.value)?.allowedActions.includes("END"));
const description = computed(() => props.lang === "en" ? chosen.value?.action.description : ({
  MARK_REVIEWED: "记录模拟审查，由执行引擎设置已审查状态。",
  ANALYZE_RELATIONS: "记录对此目标的只读分析，不改变图谱；路径可达不代表已泄密。",
  SIMULATE_PROPAGATION: "沿启用的直接关系模拟此目标接收信息，传播来源须来自引擎提供的可选项。",
  DISABLE_SYNC: "由此系统禁用向所选对象的同步，执行引擎维护对应的出向同步关系。",
  REVOKE_SHARING: "由此人员撤销向所选对象的共享，执行引擎维护对应的出向共享关系。",
} as Record<string, string>)[chosen.value?.action.actionType ?? ""] ?? chosen.value?.action.description);
const blockedReason = computed(() => {
  const reason = chosen.value?.unavailableReason ?? "";
  if (props.lang === "en") return reason;
  return ({ "Object is already reviewed": "此对象已审查。", "Object is already simulated exposed": "此对象已标记为模拟暴露。",
    "Relation is disabled": "此关系已禁用。", "Relation is disabled or has incompatible type": "此关系已禁用或类型不适用。",
    "No visible eligible source with an enabled direct propagation path": "当前没有可见且符合条件的直接传播来源。",
    "No visible recipient with enabled outgoing sharing": "当前没有可见且启用共享的接收对象。",
    "No visible destination with enabled outgoing synchronization": "当前没有可见且启用同步的目标对象。",
  } as Record<string, string>)[reason] ?? reason;
});

watch(() => props.agents, () => {
  if (!props.agents.some(a => a.id === agentId.value)) agentId.value = props.agents[0]?.id ?? "";
}, { immediate: true });
watch(capabilities, () => {
  if (!targets.value.some(c => key(c) === target.value)) {
    const first = capabilities.value.find(c => c.available) ?? targets.value[0];
    target.value = first ? key(first) : "";
  }
}, { immediate: true });
watch(actions, () => {
  if (!actions.value.some(c => c.action.actionType === actionId.value))
    actionId.value = (actions.value.find(c => c.available) ?? actions.value[0])?.action.actionType ?? "";
}, { immediate: true });
watch(chosen, () => {
  parameterValues.value = Object.fromEntries(parameterNames.value.map(name => {
    const options = parameterOptions.value[name] ?? [];
    const previous = parameterValues.value[name] ?? "";
    return [name, parameterOptions.value[name] ? (options.includes(previous) ? previous : options[0] ?? '') : previous];
  }));
}, { immediate: true });
watch(() => props.targetId, id => {
  const match = targets.value.find(c => c.targetKind === "ENTITY" && c.targetId === id);
  if (match) target.value = key(match);
});
function submit(end = false) {
  if (props.busy || (!end && !canSubmit.value) || (end && !canEnd.value)) return;
  emit("submit", { agentId: agentId.value, expectedVersion: props.catalog.version, decision: {
    actionType: end ? "END" : chosen.value!.action.actionType,
    targetKind: end ? "AGENT" : "ENTITY",
    targetId: end ? "" : chosen.value!.targetId,
    parameters: end ? {} : Object.fromEntries(parameterNames.value.filter(name => parameterValues.value[name] !== '').map(name => {
      const value = parameterValues.value[name];
      return [name, fields.value[name]?.type === 'boolean' ? value === 'true' : ['integer', 'number'].includes(fields.value[name]?.type ?? '') ? Number(value) : value];
    })),
    reason: "User-requested simulated action",
  } });
}
</script>

<template>
  <form class="panel grid gap-4" @submit.prevent="submit()">
    <h3 class="font-semibold">{{ t.title }}</h3>
    <p class="text-sm text-slate-500">{{ t.help }}</p>
    <fieldset :disabled="busy" class="grid gap-4 md:grid-cols-3">
      <label>{{ t.agent }}<select v-model="agentId" :aria-label="t.agent"><option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agentDisplayName(agent, lang) }}</option></select></label>
      <label>{{ t.target }}<select v-model="target" :aria-label="t.target"><option v-for="item in targets" :key="key(item)" :value="key(item)">{{ item.targetName }} · {{ item.type }}</option></select></label>
      <label>{{ t.action }}<select v-model="actionId" :aria-label="t.action"><option v-for="item in actions" :key="item.action.actionType" :value="item.action.actionType">{{ lang === 'zh' ? item.action.nameZh : item.action.nameEn }} · {{ item.action.actionType }}</option></select></label>
    </fieldset>
    <p v-if="!capabilities.length" role="status">{{ t.empty }}</p>
    <template v-if="chosen">
      <p class="text-sm">{{ description }}</p>
      <p v-if="!chosen.available" role="status" class="text-amber-800">{{ blockedReason }}</p>
      <label v-for="name in parameterNames" :key="name">{{ parameterLabel(name) }}
        <select v-if="parameterOptions[name] || fields[name]?.enum || fields[name]?.type === 'boolean'" v-model="parameterValues[name]" :aria-label="parameterLabel(name)" :disabled="busy || !chosen.available">
          <option value="">{{ lang === 'zh' ? '请选择' : 'Select' }}</option>
          <option v-for="value in parameterOptions[name] ?? fields[name]?.enum ?? ['true', 'false']" :key="String(value)" :value="String(value)">{{ String(value) }}</option>
        </select>
        <input v-else v-model="parameterValues[name]" :type="['integer', 'number'].includes(fields[name]?.type ?? '') ? 'number' : 'text'" :step="fields[name]?.type === 'integer' ? '1' : 'any'" :aria-label="parameterLabel(name)" :required="required.includes(name)" :disabled="busy || !chosen.available" />
      </label>
      <p v-if="!parameterNames.length" class="text-xs text-slate-500">{{ t.noParameters }}</p>
      <details><summary class="cursor-pointer text-xs">{{ t.schema }}</summary><pre class="overflow-auto text-xs">{{ JSON.stringify(chosen.action.parametersSchema, null, 2) }}</pre></details>
    </template>
    <div class="flex gap-3">
      <button class="primary" :disabled="busy || !canSubmit">{{ t.submit }}</button>
      <button v-if="canEnd" type="button" class="secondary" :disabled="busy" @click="submit(true)">{{ t.end }}</button>
    </div>
  </form>
</template>

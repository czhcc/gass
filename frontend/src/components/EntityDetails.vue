<script setup lang="ts">
import { computed } from "vue";
import { effectDescription, conditionDescription, parameterDetails, parameterLabel, unavailableDescription } from "../actionDescriptions";
import type { ActionCatalog, ActionDefinition, Entity, Lang, Ontology } from "../types";
const props = withDefaults(defineProps<{ entity: Entity; lang: Lang; ontology?: Ontology | null; catalog?: ActionCatalog; live?: boolean }>(), { live: true });
const capabilities = computed(() => (props.catalog?.agents ?? []).flatMap(agent => agent.capabilities
  .filter(c => c.targetKind === "ENTITY" && c.targetId === props.entity.id)
  .map(c => ({ ...c, agentId: agent.agentId, ended: agent.ended }))));
const actions = computed(() => {
  const result = new Map<string, ActionDefinition>();
  for (const type of props.ontology?.types ?? [])
    if (type.targetKind === "ENTITY" && type.type === props.entity.type)
      for (const action of type.actions) result.set(action.actionType, action);
  for (const c of capabilities.value) result.set(c.action.actionType, c.action);
  return [...result.values()];
});
const pretty = (value: unknown) => JSON.stringify(value, null, 2);
</script>
<template>
  <section class="mt-3 space-y-4" aria-label="Object details">
    <pre class="max-h-56 overflow-auto text-xs">{{ pretty(entity) }}</pre>
    <h4 class="font-semibold">{{ lang === 'zh' ? '对象 Action 详情' : 'Object action details' }}</h4>
    <p v-if="catalog" class="text-xs text-slate-500">{{ lang === 'zh' ? '以下展示本运行中 Agent 可见的对象 Action。' : 'Actions visible to agents in this run.' }}</p>
    <p v-if="!live" class="text-xs text-amber-700">{{ lang === 'zh' ? '历史回放不评估动作可用性；以下仅展示动作定义。' : 'Availability is not evaluated for historical playback; only action definitions are shown.' }}</p>
    <p v-if="!actions.length" class="text-sm text-slate-500">{{ lang === 'zh' ? '暂无可展示的对象 Action。' : 'No object actions to display.' }}</p>
    <article v-for="action in actions" :key="action.actionType" class="rounded-lg border border-slate-200 p-3 text-sm">
      <h5 class="font-medium">{{ lang === 'zh' ? action.nameZh : action.nameEn }}</h5>
      <code class="text-xs">{{ action.actionType }}</code>
      <span v-if="action.program" class="ml-2 rounded bg-blue-50 px-2 py-1 text-xs text-blue-800">{{ lang === 'zh' ? 'CEL + 声明式执行' : 'CEL + declarative execution' }}</span>
      <p v-if="lang === 'en' || action.program" class="my-2">{{ action.description }}</p>
      <dl class="space-y-2">
        <div><dt class="font-medium">{{ lang === 'zh' ? '参数说明' : 'Parameters' }}</dt><dd>
          <p v-if="!parameterDetails(action, lang).length">{{ lang === 'zh' ? '无需填写参数。' : 'No parameters required.' }}</p>
          <ul v-else class="space-y-2"><li v-for="parameter in parameterDetails(action, lang)" :key="parameter.name" class="rounded bg-slate-50 p-2">
            <span class="font-medium">{{ parameter.label }}</span> <code class="text-xs">({{ parameter.name }})</code> · <span>{{ parameter.required ? (lang === 'zh' ? '必填' : 'Required') : (lang === 'zh' ? '选填' : 'Optional') }}</span>
            <p>{{ parameter.type }}</p><p>{{ parameter.description }}</p>
          </li></ul>
        </dd></div>
        <div><dt class="font-medium">{{ lang === 'zh' ? '执行前置条件' : 'Preconditions' }}</dt><dd class="break-words">
          <ul v-if="action.program?.conditions.length"><li v-for="(condition, i) in action.program.conditions" :key="i">{{ lang === 'zh' ? condition.messageZh : condition.messageEn }}</li></ul>
          <ul v-else-if="action.preconditions.length"><li v-for="condition in action.preconditions" :key="condition">{{ conditionDescription(condition, lang) }}</li></ul>
          <p v-else>{{ lang === 'zh' ? '无额外状态条件，仍需通过执行权限、对象可见性和参数校验。' : 'No additional state conditions; permissions, visibility and parameters are still validated.' }}</p>
        </dd></div>
        <div><dt class="font-medium">{{ lang === 'zh' ? '执行效果' : 'Effect' }}</dt><dd class="break-words">{{ effectDescription(action, lang) }}</dd></div>
      </dl>
      <details class="mt-3 text-xs text-slate-500"><summary class="cursor-pointer">{{ lang === 'zh' ? '技术定义（JSON 与编码）' : 'Technical definition (JSON and codes)' }}</summary><pre class="mt-2 overflow-auto">{{ pretty({ parametersSchema: action.parametersSchema, preconditions: action.preconditions, effect: action.effect, program: action.program }) }}</pre></details>
      <template v-if="live">
        <div v-for="c in capabilities.filter(c => c.action.actionType === action.actionType)" :key="c.agentId" class="mt-3 border-t pt-2 text-xs">
          <p>{{ c.agentId }} · {{ c.ended ? (lang === 'zh' ? 'Agent 已结束' : 'Agent ended') : c.available ? (lang === 'zh' ? '可执行' : 'Available') : (lang === 'zh' ? '不可执行' : 'Unavailable') }}<span v-if="!c.available"> · {{ unavailableDescription(c.unavailableReason, lang) }}</span></p>
          <template v-if="c.parameterOptions && Object.keys(c.parameterOptions).length"><p>{{ lang === 'zh' ? '当前可选关联对象' : 'Current related object options' }}</p><p v-for="(ids, name) in c.parameterOptions" :key="name">{{ parameterLabel(name, lang) }}：{{ ids.join('、') || (lang === 'zh' ? '暂无可选对象' : 'No available objects') }}</p></template>
        </div>
      </template>
    </article>
  </section>
</template>

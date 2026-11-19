<script setup lang="ts">
import { computed } from "vue";
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
      <p class="my-2">{{ action.description }}</p>
      <dl class="space-y-2">
        <div><dt class="font-medium">{{ lang === 'zh' ? '参数定义（required 为必填项）' : 'Parameter schema (required fields)' }}</dt><dd><pre class="overflow-auto text-xs">{{ pretty(action.parametersSchema) }}</pre></dd></div>
        <div><dt class="font-medium">{{ lang === 'zh' ? '执行前置条件' : 'Preconditions' }}</dt><dd class="break-words">{{ action.preconditions.join(', ') || (lang === 'zh' ? '无' : 'None') }}</dd></div>
        <div><dt class="font-medium">{{ lang === 'zh' ? '执行效果' : 'Effect' }}</dt><dd class="break-words">{{ action.effect }}</dd></div>
      </dl>
      <template v-if="live">
        <div v-for="c in capabilities.filter(c => c.action.actionType === action.actionType)" :key="c.agentId" class="mt-3 border-t pt-2 text-xs">
          <p>{{ c.agentId }} · {{ c.ended ? (lang === 'zh' ? 'Agent 已结束' : 'Agent ended') : c.available ? (lang === 'zh' ? '可执行' : 'Available') : (lang === 'zh' ? '不可执行' : 'Unavailable') }}<span v-if="!c.available"> · {{ c.unavailableReason }}</span></p>
          <template v-if="c.parameterOptions && Object.keys(c.parameterOptions).length"><p>{{ lang === 'zh' ? '当前可选关联对象' : 'Current related object options' }}</p><pre class="overflow-auto">{{ pretty(c.parameterOptions) }}</pre></template>
        </div>
      </template>
    </article>
  </section>
</template>

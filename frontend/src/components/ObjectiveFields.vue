<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { api } from '../api';
import type { GoalCondition, Lang, ObjectiveDraft } from '../types';

const props = defineProps<{ lang: Lang; worldId: string; modelId: string; maxSteps: number | string; busy: boolean; validRounds: boolean }>();
const objective = defineModel<string>('objective', { required: true });
const conditions = defineModel<GoalCondition[]>('conditions', { required: true });
const generating = defineModel<boolean>('generating', { required: true });
const draft = ref<ObjectiveDraft>();
const error = ref('');
let request = 0;
let conditionNumber = 0;
const text = (zh: string, en: string) => props.lang === 'zh' ? zh : en;
const canDraft = computed(() => !!(props.worldId && props.modelId && props.validRounds && objective.value.trim()));
watch(() => [objective.value, props.worldId, props.modelId, props.maxSteps], () => {
  request++;
  generating.value = false;
  draft.value = undefined;
  error.value = '';
});
onBeforeUnmount(() => { request++; generating.value = false; });

async function generate() {
  if (!canDraft.value || props.busy || generating.value) return;
  const current = ++request;
  error.value = '';
  generating.value = true;
  try {
    const value = await api<ObjectiveDraft>('/scenario-objectives/draft', 'POST', {
      worldId: props.worldId, modelId: props.modelId, objective: objective.value.trim(), maxSteps: props.maxSteps,
    });
    if (current !== request) return;
    draft.value = value;
    conditions.value = value.conditions;
  } catch (cause) {
    if (current === request) error.value = String(cause);
  } finally {
    if (current === request) generating.value = false;
  }
}
function addCondition() {
  let id: string;
  do { id = `manual_${++conditionNumber}`; } while (conditions.value.some(condition => condition.id === id));
  conditions.value = [...conditions.value, { id, nameZh: '', nameEn: '', expression: '', evidenceExpression: '' }];
}
</script>

<template>
  <fieldset class="grid gap-3 rounded border border-slate-200 p-3" :disabled="busy">
    <legend class="px-1 font-medium">{{ text('目标与判定条件', 'Objective and conditions') }}</legend>
    <label>{{ text('推演目标', 'Simulation objective') }}
      <textarea v-model="objective" rows="3" maxlength="2000" :placeholder="text('写出截止轮次要验证的问题，以及希望看到的依据。', 'Describe what to verify at the deadline and the evidence you want to see.')" />
    </label>
    <template v-if="objective.trim()">
      <div class="flex flex-wrap gap-2">
        <button type="button" class="secondary" :disabled="!canDraft || generating" @click="generate">
          {{ generating ? text('正在生成…', 'Drafting…') : text('生成判定条件', 'Draft conditions') }}
        </button>
        <button type="button" class="secondary" :disabled="generating || conditions.length >= 10" @click="addCondition">{{ text('添加条件', 'Add condition') }}</button>
      </div>
      <p class="text-xs text-slate-600">{{ conditions.length
        ? text('引擎在截止轮检查全部条件。重新生成会替换当前条件。', 'The engine checks every condition at the horizon. Drafting again replaces the current conditions.')
        : text('没有条件时仍可保存目标，结果页会显示“无可判定条件”和主研判结论。', 'An objective with no conditions can still be saved. Results show that no engine conditions were set, alongside the master review.') }}</p>
      <p v-if="draft?.mode === 'MOCK'" class="text-sm" role="status">{{ text('模拟模型提供所选领域的模板条件，请按目标编辑。', 'The mock model supplies template conditions for this domain. Edit them to match your objective.') }}</p>
      <div v-if="draft?.unresolved.length" class="rounded border border-amber-200 bg-amber-50 p-2 text-sm" role="status">
        <p class="font-medium">{{ text('需要补充的内容', 'Requirements needing clarification') }}</p>
        <ul class="ml-5 list-disc"><li v-for="(item, index) in draft.unresolved" :key="index">{{ item }}</li></ul>
      </div>
      <p v-if="error" class="break-words text-sm text-red-700" role="alert">{{ error }}</p>
      <fieldset v-for="(condition, index) in conditions" :key="condition.id" class="grid gap-2 rounded border border-slate-200 p-3" :disabled="generating">
        <legend class="px-1 text-sm">{{ text('判定条件', 'Condition') }} {{ index + 1 }}</legend>
        <label>{{ text('中文名称', 'Chinese label') }}<input v-model="condition.nameZh" maxlength="200" /></label>
        <label>{{ text('英文名称', 'English label') }}<input v-model="condition.nameEn" maxlength="200" /></label>
        <label>{{ text('布尔判定式（CEL）', 'Boolean condition (CEL)') }}<textarea v-model="condition.expression" maxlength="2048" rows="2" required /></label>
        <label>{{ text('依据取值式（CEL，可选）', 'Evidence value (CEL, optional)') }}<textarea v-model="condition.evidenceExpression" maxlength="2048" rows="2" /></label>
        <button type="button" class="justify-self-start text-sm text-red-700" @click="conditions = conditions.filter(entry => entry.id !== condition.id)">{{ text('移除条件', 'Remove condition') }}</button>
      </fieldset>
    </template>
  </fieldset>
</template>

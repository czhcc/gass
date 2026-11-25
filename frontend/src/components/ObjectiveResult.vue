<script setup lang="ts">
import { computed } from 'vue';
import type { BusinessOutcome, Lang, ObjectiveReview, RunEvent } from '../types';
import { readableNarrative, type NarrativeContext } from '../taskNarrative';

const props = defineProps<{ objective: string; maxSteps: number; events: RunEvent[]; titles: Map<number, { title: string }>; lang: Lang; context?: NarrativeContext }>();
const emit = defineEmits<{ evidence: [sequence: number] }>();
const text = (zh: string, en: string) => props.lang === 'zh' ? zh : en;
const title = computed(() => text('目标与推演结果', 'Objective results'));
const outcome = computed(() => [...props.events].reverse().find(event => event.eventType === 'SCENARIO_EVALUATED')?.payload.outcome as BusinessOutcome | undefined);
const reviewEvent = computed(() => props.events.find(event => event.eventType === 'OBJECTIVE_REVIEWED'));
const review = computed(() => reviewEvent.value?.payload.review as ObjectiveReview | undefined);
const conclusion = computed(() => readableNarrative(review.value?.conclusion, props.lang, props.context));
const limitations = computed(() => readableNarrative(review.value?.limitations, props.lang, props.context));
const history = computed(() => readableNarrative(review.value?.history ?? undefined, props.lang, props.context));
const finalState = computed(() => readableNarrative(review.value?.finalState ?? undefined, props.lang, props.context));
const verdict = computed(() => {
  switch (outcome.value?.result) {
    case 'SUCCEEDED': return text('已达成', 'Achieved');
    case 'FAILED': return text('未达成', 'Not achieved');
    case 'ERROR': return text('判定出错', 'Evaluation error');
    case 'NO_CONDITIONS': return text('无可判定条件', 'No engine conditions');
    default: return text('此回放位置尚未判定', 'Not evaluated at this position');
  }
});
const evidenceText = (value: unknown) => typeof value === 'string' ? value : JSON.stringify(value, null, 2);
</script>

<template>
  <section class="panel min-w-0" role="region" :aria-label="title">
    <h3 class="font-semibold">{{ title }}</h3>
    <p class="mt-2 whitespace-pre-wrap break-words text-sm">{{ objective }}</p>
    <p class="mt-1 text-xs text-slate-600">{{ outcome
      ? text(`第 ${outcome.evaluatedRound} 轮结束后判定`, `Evaluated after round ${outcome.evaluatedRound}`)
      : text(`最迟在第 ${maxSteps} 轮结束时判定`, `Evaluation by the end of round ${maxSteps}`) }}</p>
    <div class="mt-4 grid gap-3 xl:grid-cols-2">
      <article class="min-w-0 rounded border border-slate-200 p-3">
        <h4 class="font-medium">{{ text('引擎判定', 'Engine verdict') }}</h4>
        <p class="mt-2 text-sm" role="status">{{ verdict }}<span v-if="outcome" class="ml-2 text-xs text-slate-600">{{ text('状态版本', 'State version') }} {{ outcome.version }}</span></p>
        <ul v-if="outcome?.conditions.length" class="mt-2 space-y-2 text-sm">
          <li v-for="condition in outcome.conditions" :key="condition.id" class="rounded bg-slate-50 p-2 break-words">
            <p>{{ lang === 'zh' ? condition.nameZh : condition.nameEn }} · {{ condition.error ? text('计算失败', 'Error') : condition.satisfied ? text('满足', 'Met') : text('未满足', 'Unmet') }}</p>
            <div v-if="condition.evidence !== null && condition.evidence !== undefined" class="mt-1 text-xs text-slate-600">
              {{ text('实际依据', 'Observed evidence') }}<pre class="mt-1 whitespace-pre-wrap break-all">{{ evidenceText(condition.evidence) }}</pre>
            </div>
            <p v-if="condition.evidenceError" class="mt-1 text-xs text-amber-700">{{ text('依据取值失败', 'Evidence value unavailable') }}</p>
          </li>
        </ul>
      </article>
      <article class="min-w-0 rounded border border-slate-200 p-3">
        <h4 class="font-medium">{{ text('主研判 Agent 结论', 'Master agent conclusion') }}</h4>
        <template v-if="review">
          <p v-if="reviewEvent?.payload.mode === 'MOCK'" class="mt-1 text-xs text-slate-600">{{ text('模拟模型', 'Mock model') }}</p>
          <p class="mt-2 whitespace-pre-wrap break-words text-sm">{{ conclusion }}</p>
          <div v-if="history" class="mt-3 text-sm">
            <h5 class="font-medium">{{ text('历史动作', 'Action history') }}</h5>
            <p class="mt-1 whitespace-pre-wrap break-words">{{ history }}</p>
          </div>
          <div v-if="finalState" class="mt-3 text-sm">
            <h5 class="font-medium">{{ text('结束时的图谱', 'Graph at the end') }}</h5>
            <p class="mt-1 whitespace-pre-wrap break-words">{{ finalState }}</p>
          </div>
          <ul class="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-sm" :aria-label="text('研判依据', 'Cited evidence')">
            <li v-for="sequence in review.evidenceSequences" :key="sequence">
              <button type="button" class="text-blue-700" @click="emit('evidence', sequence)">#{{ sequence }} {{ titles.get(sequence)?.title }}</button>
            </li>
          </ul>
          <p v-if="limitations" class="mt-2 whitespace-pre-wrap break-words text-xs text-slate-600">{{ limitations }}</p>
          <details v-if="conclusion !== review.conclusion || limitations !== (review.limitations ?? '') || history !== (review.history ?? '') || finalState !== (review.finalState ?? '')" class="mt-3 text-xs text-slate-600">
            <summary class="cursor-pointer">{{ text('原始研判文本', 'Original review text') }}</summary>
            <p class="mt-2 whitespace-pre-wrap break-words">{{ review.conclusion }}</p>
            <p v-if="history !== (review.history ?? '')" class="mt-2 whitespace-pre-wrap break-words">{{ review.history }}</p>
            <p v-if="finalState !== (review.finalState ?? '')" class="mt-2 whitespace-pre-wrap break-words">{{ review.finalState }}</p>
            <p v-if="review.limitations" class="mt-2 whitespace-pre-wrap break-words">{{ review.limitations }}</p>
          </details>
        </template>
        <p v-else class="mt-2 text-sm text-slate-600" role="status">{{ reviewEvent?.payload.status === 'UNAVAILABLE'
          ? text('未能生成有可核验依据的结论。', 'A conclusion with verifiable references could not be generated.')
          : text('此回放位置尚未记录最终研判。', 'No final review recorded at this position.') }}</p>
      </article>
    </div>
  </section>
</template>

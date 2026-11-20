<script setup lang="ts">
import { computed } from 'vue';
import { describeEvent, type EventContext } from '../eventPresentation';
import type { Lang, RunEvent } from '../types';
const props = defineProps<{ event: RunEvent; context: EventContext; lang: Lang }>();
const detail = computed(() => describeEvent(props.event, props.context, props.lang));
</script>
<template>
  <section class="space-y-3 rounded-lg border p-4 text-sm" :class="detail.tone === 'danger' ? 'border-red-200 bg-red-50/40' : detail.tone === 'warning' ? 'border-amber-200 bg-amber-50/40' : 'border-slate-200 bg-slate-50/40'" :aria-label="lang === 'zh' ? '事件详情' : 'Event details'">
    <div class="flex flex-wrap items-center gap-3"><h4 class="font-semibold">{{ detail.title }}</h4><span class="text-xs text-slate-500">#{{ event.sequence }} · {{ lang === 'zh' ? '模拟时间' : 'Simulation time' }} {{ event.simulationTime }}</span></div>
    <p class="text-xs text-slate-500">{{ lang === 'zh' ? '执行方' : 'Actor' }}：<span>{{ detail.actor }}</span></p>
    <p class="leading-relaxed">{{ detail.summary }}</p>
    <dl class="grid gap-2 sm:grid-cols-2"><div v-for="fact in detail.facts" :key="fact.label" class="min-w-0"><dt class="text-xs text-slate-500">{{ fact.label }}</dt><dd class="break-words">{{ fact.value }}</dd></div></dl>
    <div v-if="detail.changes.length"><h5 class="mb-2 font-medium">{{ lang === 'zh' ? '实际变更' : 'Committed changes' }}</h5><ul class="list-disc space-y-1 pl-5"><li v-for="(change, i) in detail.changes" :key="i" class="break-words">{{ change }}</li></ul></div>
    <details v-if="detail.reasoning"><summary class="cursor-pointer text-slate-600">{{ lang === 'zh' ? '行动理由（原文）' : 'Action rationale (original)' }}</summary><p class="mt-2 whitespace-pre-wrap break-words leading-relaxed">{{ detail.reasoning }}</p></details>
    <details v-if="detail.originalReason"><summary class="cursor-pointer text-slate-600">{{ lang === 'zh' ? '引擎原始说明' : 'Original engine explanation' }}</summary><p class="mt-2 whitespace-pre-wrap break-words">{{ detail.originalReason }}</p></details>
    <details><summary class="cursor-pointer text-xs text-slate-500">{{ lang === 'zh' ? '技术详情（原始事件 JSON）' : 'Technical details (original event JSON)' }}</summary><pre class="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-all text-xs">{{ JSON.stringify(event, null, 2) }}</pre></details>
  </section>
</template>

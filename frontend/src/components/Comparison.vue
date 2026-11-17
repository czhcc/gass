<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts/core";
import { BarChart } from "echarts/charts";
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type { CompareResult, Lang } from "../types";
echarts.use([
  BarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
]);
const props = defineProps<{ result: CompareResult; lang: Lang }>();
const container = ref<HTMLDivElement>();
let chart: echarts.ECharts | undefined;
let observer: ResizeObserver | undefined;
const metrics = computed(() => {
  function values(m: Record<string, unknown>) {
    const balances = m.balancesMinor as Record<string, number> | undefined;
    return balances
      ? Object.fromEntries(
          Object.entries(balances).map(([id, v]) => [id, v / 100]),
        )
      : {
          potential: Array.isArray(m.potentialReachableIds)
            ? m.potentialReachableIds.length
            : 0,
          exposed: Array.isArray(m.simulatedExposureIds)
            ? m.simulatedExposureIds.length
            : 0,
          disabled: Array.isArray(m.simulatedDisabledRelationIds)
            ? m.simulatedDisabledRelationIds.length
            : 0,
        };
  }
  return {
    left: values(props.result.leftMetrics),
    right: values(props.result.rightMetrics),
  };
});
function metricLabel(name: string) {
  const labels: Record<string, string> =
    props.lang === "zh"
      ? { potential: "潜在可达", exposed: "模拟暴露", disabled: "关闭关系" }
      : {
          potential: "Potential reach",
          exposed: "Simulated exposure",
          disabled: "Disabled relations",
        };
  return labels[name] ?? name;
}
function update() {
  if (!container.value) return;
  chart ??= echarts.init(container.value);
  const names = [
    ...new Set([
      ...Object.keys(metrics.value.left),
      ...Object.keys(metrics.value.right),
    ]),
  ];
  chart.setOption({
    tooltip: { trigger: "axis" },
    legend: { data: ["A", "B"], top: 0 },
    grid: { left: 70, right: 20, top: 45, bottom: 55, containLabel: true },
    xAxis: { type: "category", data: names.map(metricLabel) },
    yAxis: { type: "value" },
    series: [
      {
        name: "A",
        type: "bar",
        data: names.map((n) => metrics.value.left[n] ?? 0),
        itemStyle: { color: "#2563eb" },
      },
      {
        name: "B",
        type: "bar",
        data: names.map((n) => metrics.value.right[n] ?? 0),
        itemStyle: { color: "#10b981" },
      },
    ],
  });
}
onMounted(() => {
  update();
  if (typeof ResizeObserver !== "undefined") {
    observer = new ResizeObserver(() => chart?.resize());
    observer.observe(container.value!);
  }
});
watch(() => [props.result, props.lang], update);
onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
});
</script>
<template>
  <article class="panel">
    <h3 class="font-semibold">
      {{
        lang === "zh" ? "分支结果比较（模拟）" : "Branch comparison (simulated)"
      }}
    </h3>
    <p class="mt-3 break-all text-sm">
      A: {{ result.leftId }}<br />B: {{ result.rightId }}
    </p>
    <p class="my-3 text-sm">
      {{
        lang === "zh"
          ? `实体差异 ${result.entityDifferences.length}；关系差异 ${result.relationDifferences.length}。基于持久化状态，不代表真实世界事实。`
          : result.summary
      }}
    </p>
    <div
      ref="container"
      class="h-72"
      role="img"
      :aria-label="lang === 'zh' ? '分支指标对比' : 'Branch metrics comparison'"
    />
    <table class="w-full text-left text-sm">
      <thead>
        <tr>
          <th class="p-2">{{ lang === "zh" ? "指标" : "Metric" }}</th>
          <th>A</th>
          <th>B</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="name in Object.keys(metrics.left)" :key="name">
          <td class="p-2">{{ metricLabel(name) }}</td>
          <td>{{ metrics.left[name] }}</td>
          <td>{{ metrics.right[name] ?? 0 }}</td>
        </tr>
      </tbody>
    </table>
    <details
      v-for="difference in [
        ...result.entityDifferences,
        ...result.relationDifferences,
      ]"
      :key="difference.id"
      class="mt-3 border-t border-slate-100 pt-3"
    >
      <summary>{{ difference.id }}</summary>
      <div class="mt-2 grid gap-4 md:grid-cols-2">
        <pre class="max-h-56 overflow-auto text-xs">
A: {{ JSON.stringify(difference.before, null, 2) }}</pre
        >
        <pre class="max-h-56 overflow-auto text-xs">
B: {{ JSON.stringify(difference.after, null, 2) }}</pre
        >
      </div>
    </details>
  </article>
</template>

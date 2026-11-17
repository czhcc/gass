<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from "vue";
import cytoscape from "cytoscape";
import type { Entity, Lang, World } from "../types";
const props = withDefaults(
  defineProps<{
    world: World;
    lang?: Lang;
    highlightId?: string;
    baselineIds?: string[];
  }>(),
  { lang: "zh", highlightId: "" },
);
const emit = defineEmits<{ select: [id: string] }>();
const element = ref<HTMLDivElement>();
let graph: cytoscape.Core | undefined;
let resize: ResizeObserver | undefined;
function label(entity: Entity) {
  let name = entity.name;
  if (typeof entity.properties.balanceMinor === "number")
    name +=
      "\n" +
      (entity.properties.balanceMinor / 100).toLocaleString(
        props.lang === "zh" ? "zh-CN" : "en-US",
      ) +
      " " +
      String(entity.properties.currency ?? "");
  if (entity.properties.exposed)
    name += props.lang === "zh" ? "\n[模拟暴露]" : "\n[simulated exposure]";
  else if (entity.properties.reviewed)
    name += props.lang === "zh" ? "\n[已审核]" : "\n[reviewed]";
  if (entity.properties.riskFlag)
    name +=
      props.lang === "zh" ? "\n[模拟风险标记]" : "\n[simulated risk flag]";
  return name;
}
function update() {
  if (!element.value) return;
  if (!graph) {
    graph = cytoscape({
      container: element.value,
      elements: [],
      style: [
        {
          selector: "node",
          style: {
            label: "data(label)",
            "text-wrap": "wrap",
            "text-max-width": "140px",
            "background-color": "#64748b",
            color: "#172338",
            "font-size": 11,
            "text-valign": "bottom",
            "text-margin-y": 8,
            "transition-property": "background-color, border-color",
            "transition-duration": 200,
          },
        },
        {
          selector: "edge",
          style: {
            label: "data(label)",
            "target-arrow-shape": "triangle",
            "curve-style": "bezier",
            width: 1.5,
            "line-color": "#94a3b8",
            "target-arrow-color": "#94a3b8",
            "font-size": 8,
          },
        },
        { selector: "node.reviewed", style: { "background-color": "#f59e0b" } },
        { selector: "node.exposed", style: { "background-color": "#dc2626" } },
        {
          selector: "node.added",
          style: { "border-width": 3, "border-color": "#10b981" },
        },
        {
          selector: "node.focused",
          style: { "border-width": 4, "border-color": "#2563eb" },
        },
        {
          selector: "edge.focused",
          style: {
            "line-color": "#2563eb",
            "target-arrow-color": "#2563eb",
            width: 3,
          },
        },
        { selector: "edge.hypothesis", style: { "line-style": "dashed" } },
        {
          selector: "edge.disabled",
          style: { opacity: 0.35, "line-style": "dashed" },
        },
      ],
    });
    graph.on("tap", "node", (e) => emit("select", e.target.id()));
  }
  const next = [
    ...props.world.entities.map((e) => ({
      data: { id: e.id, label: label(e) },
      classes: [
        e.properties.reviewed ? "reviewed" : "",
        e.properties.exposed ? "exposed" : "",
        props.baselineIds && !props.baselineIds.includes(e.id) ? "added" : "",
        props.highlightId === e.id ? "focused" : "",
      ]
        .filter(Boolean)
        .join(" "),
    })),
    ...props.world.relations.map((e) => ({
      data: {
        id: e.id,
        source: e.sourceId,
        target: e.targetId,
        label:
          e.relationType +
          (!e.enabled
            ? props.lang === "zh"
              ? " [已关闭]"
              : " [disabled]"
            : ""),
      },
      classes: [
        e.enabled ? "" : "disabled",
        e.evidence === "HYPOTHESIS" ? "hypothesis" : "",
        props.highlightId === e.id ? "focused" : "",
      ]
        .filter(Boolean)
        .join(" "),
    })),
  ];
  const ids = new Set(next.map((e) => e.data.id));
  let added = false;
  graph.batch(() => {
    graph!
      .elements()
      .filter((e) => !ids.has(e.id()))
      .remove();
    for (const item of next) {
      const existing = graph!.getElementById(item.data.id);
      if (existing.length) {
        existing.data(item.data);
        existing.classes(item.classes);
      } else {
        graph!.add(item);
        added = true;
      }
    }
  });
  if (added) graph.layout({ name: "circle", padding: 55 }).run();
}
onMounted(() => {
  update();
  if (typeof ResizeObserver !== "undefined") {
    resize = new ResizeObserver(() => {
      graph?.resize();
      graph?.layout({ name: "circle", padding: 55 }).run();
      graph?.fit(undefined, 45);
    });
    resize.observe(element.value!);
  }
});
watch(() => [props.world, props.lang, props.highlightId], update, {
  deep: true,
});
onBeforeUnmount(() => {
  resize?.disconnect();
  graph?.destroy();
});
</script>
<template>
  <div>
    <div
      ref="element"
      class="h-[460px] w-full"
      role="img"
      :aria-label="world.name"
    />
    <p class="mt-3 text-xs text-slate-600">
      {{
        lang === "zh"
          ? "图例：蓝灰=普通对象；蓝框=当前行动；橙=已审核；红=模拟暴露；绿框=新增对象；虚线/半透明=假设或关闭关系。模拟不等于事实。"
          : "Legend: slate=object; blue border=current action; amber=reviewed; red=simulated exposure; green border=new object; dashed/faded=hypothesis or disabled edge. Simulation is not fact."
      }}
    </p>
  </div>
</template>

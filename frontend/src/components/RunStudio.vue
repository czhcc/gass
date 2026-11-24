<script setup lang="ts">
import {
  computed,
  defineAsyncComponent,
  onBeforeUnmount,
  onMounted,
  nextTick,
  ref,
} from "vue";
import { api, ApiError } from "../api";
import { mergeEvents } from "../playback";
import ObjectActions from "./ObjectActions.vue";
import EntityDetails from "./EntityDetails.vue";
import EventDetails from './EventDetails.vue';
import HierarchyTrace from './HierarchyTrace.vue';
import ObjectiveResult from './ObjectiveResult.vue';
import { describeEvent, failureDiagnosticMessages, isKeyEvent, terminationMessages } from '../eventPresentation';
import { agentDisplayName, agentGoalDescription, modelModeLabel } from '../agentPresentation';
import type {
  Agent,
  ActionCatalog,
  ObjectActionInput,
  CompareResult,
  Lang,
  Run,
  RunEvent,
  RunState,
  TaskRecord,
} from "../types";
const Graph = defineAsyncComponent(() => import("./Graph.vue"));
const Comparison = defineAsyncComponent(() => import("./Comparison.vue"));
const props = defineProps<{ lang: Lang; initialId?: string }>();
const actionCatalog = ref<ActionCatalog>();
const tasks = ref<TaskRecord[]>([]);
const eventDetailsTarget = ref<HTMLElement>();
const runs = ref<Run[]>([]),
  selected = ref(props.initialId ?? ""),
  info = ref<Run>(),
  agents = ref<Agent[]>([]),
  events = ref<RunEvent[]>([]),
  state = ref<RunState>(),
  baselineIds = ref<string[]>([]),
  cursor = ref(0),
  live = ref(true),
  playing = ref(false),
  speed = ref(1),
  busy = ref(false),
  error = ref(""),
  connection = ref(""),
  selectedEntityId = ref(""),
  label = ref(""),
  other = ref(""),
  comparison = ref<CompareResult>(),
  branchLabels = ref<Record<string, string>>({});
const intervention = ref({
  agentId: "",
  actionType: "",
  targetId: "",
  parameters: "{}",
});
const selectedEntity = computed(() => state.value?.entities.find(e => e.id === selectedEntityId.value));
const deletionId = ref<string | null>(null), deletionError = ref("");
const dialog = ref<HTMLElement>(), cancelButton = ref<HTMLButtonElement>(), runSelect = ref<HTMLSelectElement>();
let deletionTrigger: HTMLElement | undefined;
let source: EventSource | undefined,
  timer: ReturnType<typeof setTimeout> | undefined,
  revision = 0,
  taskRevision = 0,
  disposed = false;
const t = computed(() =>
  props.lang === "zh"
    ? {
        title: "推演工作台",
        run: "运行 / 分支",
        none: "请先在场景配置中创建运行",
        compute: "计算单步",
        resume: "继续计算",
        pause: "暂停计算",
        stop: "停止运行",
        refresh: "刷新",
        remove: "删除运行",
        confirm: "确认删除",
        cancel: "取消",
        deleteHelp: "将永久删除此运行及其事件、快照和回放记录，无法恢复。场景和世界会保留。有子分支时需先删除子分支；正在计算时需先暂停或停止。",
        hasBranches: "请先删除此运行的子分支。",
        activeRun: "请先暂停或停止此运行，再删除。",
        live: "查看实时状态",
        history: "历史回放（不调用模型）",
        version: "状态版本",
        time: "模拟时间",
        play: "播放",
        pauseReplay: "暂停播放",
        prev: "上一事件",
        next: "下一事件",
        position: "事件位置",
        speed: "播放速度",
        agents: "Agent 活动",
        events: "事件时间线",
        empty: "暂无事件",
        object: "对象状态",
        fork: "从此事件创建分支",
        label: "分支名称",
        compare: "比较结果",
        other: "对比运行",
        intervention: "模拟干预",
        actor: "执行 Agent",
        action: "Action",
        target: "目标 ID",
        params: "参数 JSON",
        submit: "提交模拟行动",
        notice:
          "模拟模式用于功能验证，不代表真实 LLM 自主决策。回放仅读取已持久化事件和快照。",
        connected: "实时事件已连接",
        reconnecting: "事件连接恢复中",
        reason: "观察 / 行动 / 工具 / 裁定",
        branch: "分支",
        noObject: "点击图节点查看属性",
      }
    : {
        title: "Simulation studio",
        run: "Run / branch",
        none: "Create a run in Scenarios first",
        compute: "Compute one step",
        resume: "Resume computation",
        pause: "Pause computation",
        stop: "Stop run",
        refresh: "Refresh",
        remove: "Delete run",
        confirm: "Confirm deletion",
        cancel: "Cancel",
        deleteHelp: "Permanently delete this run and its events, snapshots and replay history? This cannot be undone. The scenario and world will be retained. Delete child branches first, and pause or stop computation before deleting.",
        hasBranches: "Delete child branches first.",
        activeRun: "Pause or stop this run before deleting it.",
        live: "Show live state",
        history: "Historical replay (no model calls)",
        version: "State version",
        time: "Simulation time",
        play: "Play",
        pauseReplay: "Pause playback",
        prev: "Previous event",
        next: "Next event",
        position: "Event position",
        speed: "Playback speed",
        agents: "Agent activity",
        events: "Event timeline",
        empty: "No events",
        object: "Object state",
        fork: "Fork at this event",
        label: "Branch name",
        compare: "Compare results",
        other: "Compare run",
        intervention: "Simulated intervention",
        actor: "Acting agent",
        action: "Action",
        target: "Target ID",
        params: "Parameters JSON",
        submit: "Submit simulated action",
        notice:
          "Mock mode validates functionality and is not real LLM autonomy. Replay reads persisted events and snapshots only.",
        connected: "Live events connected",
        reconnecting: "Reconnecting events",
        reason: "Observation / action / tools / adjudication",
        branch: "Branch",
        noObject: "Select a graph node to inspect properties",
      },
);
const maxSequence = computed(() => events.value.at(-1)?.sequence ?? 0);
const shownEvents = computed(() =>
  events.value.filter((e) => e.sequence <= cursor.value),
);
const latest = computed(() => shownEvents.value.at(-1));
const eventScope = ref('key');
const eventContext = computed(() => ({ events: events.value, agents: agents.value, entities: state.value?.entities ?? [], relations: state.value?.relations ?? [], catalog: actionCatalog.value }));
const eventDescriptions = computed(() => new Map(events.value.map(event => [event.sequence, describeEvent(event, eventContext.value, props.lang)])));
const timelineEvents = computed(() => eventScope.value === 'all' ? events.value : events.value.filter(isKeyEvent));
const highlight = computed(() => {
  const p = [...shownEvents.value]
    .reverse()
    .find((e) =>
      [
        "ACTION_ACCEPTED",
        "ACTION_REJECTED",
        "USER_INTERVENTION",
        "ACTION_PROPOSED",
        "AGENT_DECISION",
        "USER_INTERVENTION",
      ].includes(e.eventType),
    )?.payload;
  const action = (p?.action ?? p?.decision) as
    | { targetId?: string }
    | undefined;
  return action?.targetId ?? String(p?.targetId ?? "");
});
const graphWorld = computed(() =>
  state.value
    ? {
        ...state.value,
        id: selected.value,
        name: t.value.title,
        domainCode: "",
        entities: state.value.entities,
        relations: state.value.relations,
      }
    : undefined,
);
const terminal = computed(
  () =>
    info.value &&
    ["COMPLETED", "BUDGET_EXHAUSTED", "STOPPED", "FAILED"].includes(info.value.status),
);
function runStatus(run: Run) {
  if (run.terminationReason === 'OBJECTIVE_NOT_VERIFIABLE') return props.lang === 'zh' ? '无可判定条件' : 'No engine conditions';
  if (run.terminationReason === 'OBJECTIVE_MET') return props.lang === 'zh' ? '效果已达成' : 'Effects achieved';
  if (run.terminationReason === 'OBJECTIVE_NOT_MET') return props.lang === 'zh' ? '效果未达成' : 'Effects not achieved';
  if (run.status === 'COMPLETED') return run.terminationReason === 'ALL_AGENTS_ENDED'
    ? (props.lang === 'zh' ? '任务完成' : 'Task completed') : (props.lang === 'zh' ? '历史已结束' : 'Ended (legacy)');
  if (run.status === 'BUDGET_EXHAUSTED') return props.lang === 'zh' ? '预算耗尽' : 'Budget exhausted';
  return run.status;
}
const terminationSummary = computed(() => {
  if (!info.value || !terminal.value) return '';
  const diagnostic = terminationEvent.value?.payload.diagnosticCode;
  if (typeof diagnostic === 'string' && failureDiagnosticMessages[diagnostic])
    return failureDiagnosticMessages[diagnostic][props.lang === 'zh' ? 0 : 1];
  const code = info.value.terminationReason;
  return code ? terminationMessages[code]?.[props.lang === 'zh' ? 0 : 1] ?? code
    : props.lang === 'zh' ? '旧记录未区分任务完成与预算耗尽，无法确定具体结束原因' : 'This legacy record did not distinguish completion from budget exhaustion; the exact reason is unknown.';
});
const terminationEvent = computed(() => [...events.value].reverse().find(e =>
  ['RUN_COMPLETED', 'RUN_BUDGET_EXHAUSTED', 'RUN_FAILED', 'RUN_STOPPED'].includes(e.eventType)));
const allowed = computed(
  () =>
    agents.value.find((a) => a.id === intervention.value.agentId)
      ?.allowedActions ?? [],
);
function stopPlayback() {
  playing.value = false;
  if (timer) clearTimeout(timer);
}
async function listRuns() {
  runs.value = await api<Run[]>("/runs");
  const branches =
    await api<Array<{ run_id: string; label: string }>>("/branches");
  branchLabels.value = Object.fromEntries(
    branches.map((b) => [b.run_id, b.label]),
  );
}
async function loadRun() {
  const generation = ++revision;
  taskRevision++;
  seekRevision++;
  source?.close();
  stopPlayback();
  comparison.value = undefined;
  error.value = "";
  selectedEntityId.value = "";
  if (!selected.value) {
    actionCatalog.value = undefined;
    info.value = undefined;
    state.value = undefined;
    agents.value = [];
    tasks.value = [];
    events.value = [];
    baselineIds.value = [];
    connection.value = "";
    cursor.value = 0;
    return;
  }
  try {
    const [r, a, e, s, baseline, catalog, taskList] = await Promise.all([
      api<Run>("/runs/" + selected.value),
      api<Agent[]>("/runs/" + selected.value + "/agents"),
      api<RunEvent[]>("/runs/" + selected.value + "/events"),
      api<RunState>("/runs/" + selected.value + "/state"),
      api<RunState>("/runs/" + selected.value + "/replay?sequence=0"),
      api<ActionCatalog>("/runs/" + selected.value + "/action-capabilities"),
      api<TaskRecord[]>("/runs/" + selected.value + "/tasks"),
    ]);
    if (generation !== revision || disposed) return;
    info.value = r;
    actionCatalog.value = catalog;
    agents.value = a;
    tasks.value = Array.isArray(taskList) ? taskList : [];
    events.value = e;
    state.value = s;
    baselineIds.value = baseline.entities.map((e) => e.id);
    cursor.value = e.at(-1)?.sequence ?? 0;
    live.value = true;
    const previousAgent =
      a.find((agent) => agent.id === intervention.value.agentId) ?? a[0];
    intervention.value.agentId = previousAgent?.id ?? "";
    if (!previousAgent?.allowedActions.includes(intervention.value.actionType))
      intervention.value.actionType = previousAgent?.allowedActions[0] ?? "";
    if (typeof EventSource !== "undefined") {
      source = new EventSource(
        "/api/runs/" + selected.value + "/stream?after=" + maxSequence.value,
      );
      source.addEventListener("simulation", (message) => {
        if (generation !== revision || disposed) return;
        const event = JSON.parse((message as MessageEvent).data) as RunEvent;
        events.value = mergeEvents(events.value, [event]);
        if (['MASTER_TASK_STARTED','SUBTASK_DISPATCHED','MASTER_TASK_COMPLETED','SUBTASK_COMPLETED'].includes(event.eventType)) {
          const taskGeneration = ++taskRevision;
          void api<TaskRecord[]>('/runs/' + selected.value + '/tasks').then(value => { if (generation === revision && taskGeneration === taskRevision) tasks.value = value; }).catch(() => {});
        }
        connection.value = t.value.connected;
        if (live.value) {
          cursor.value = maxSequence.value;
          if (state.value)
            state.value = {
              ...state.value,
              simulationTime: event.simulationTime,
            };
          if (event.eventType === "WORLD_STATE_CHANGED")
            state.value = event.payload.after as RunState;
        }
        if (event.eventType === "WORLD_STATE_CHANGED" || event.eventType === "ACTION_ACCEPTED")
          void api<ActionCatalog>("/runs/" + selected.value + "/action-capabilities")
            .then(catalog => {
              if (generation === revision && catalog?.version >= (state.value?.version ?? 0)) actionCatalog.value = catalog;
            })
            .catch(() => {});
        if (event.eventType.startsWith("RUN_"))
          void api<Run>("/runs/" + selected.value)
            .then((r) => {
              if (generation === revision) {
                info.value = r;
                runs.value = runs.value.map(run => run.id === r.id ? r : run);
              }
            })
            .catch(() => {});
      });
      source.onopen = () => {
        connection.value = t.value.connected;
      };
      source.onerror = () => {
        connection.value = t.value.reconnecting;
      };
    }
  } catch (e) {
    if (generation === revision) error.value = String(e);
  }
}
let seekRevision = 0;
async function seek(sequence: number, stop = true) {
  if (stop) stopPlayback();
  const ticket = ++seekRevision;
  const id = selected.value;
  live.value = false;
  cursor.value = Math.max(0, Math.min(maxSequence.value, sequence));
  try {
    const s = await api<RunState>(
      "/runs/" + id + "/replay?sequence=" + cursor.value,
    );
    if (ticket === seekRevision && id === selected.value && !disposed) {
      state.value = s;
      selectedEntityId.value = "";
    }
  } catch (e) {
    error.value = String(e);
  }
}
async function showObjectiveEvidence(sequence: number) {
  await seek(sequence);
  await nextTick();
  eventDetailsTarget.value?.scrollIntoView?.({ block: 'start' });
}
async function play() {
  if (playing.value) {
    stopPlayback();
    return;
  }
  if (cursor.value >= maxSequence.value) await seek(0);
  playing.value = true;
  const advance = async () => {
    if (!playing.value || disposed) return;
    if (cursor.value >= maxSequence.value) {
      stopPlayback();
      return;
    }
    await seek(cursor.value + 1, false);
    if (playing.value) timer = setTimeout(advance, 500 / speed.value);
  };
  timer = setTimeout(advance, 500 / speed.value);
}
async function command(action: string) {
  busy.value = true;
  error.value = "";
  try {
    await api(
      "/runs/" + selected.value + "/" + action,
      "POST",
      {},
      action === "step" ? { "Idempotency-Key": crypto.randomUUID() } : {},
    );
    await loadRun();
    await listRuns();
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function fork() {
  busy.value = true;
  error.value = "";
  try {
    const child = await api<Run>(
      "/runs/" + selected.value + "/fork",
      "POST",
      { sequence: cursor.value, label: label.value },
      { "Idempotency-Key": crypto.randomUUID() },
    );
    await listRuns();
    selected.value = child.id;
    label.value = "";
    await loadRun();
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function compare() {
  busy.value = true;
  error.value = "";
  try {
    comparison.value = await api<CompareResult>(
      "/runs/compare?left=" +
        encodeURIComponent(selected.value) +
        "&right=" +
        encodeURIComponent(other.value),
    );
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function intervene() {
  busy.value = true;
  error.value = "";
  try {
    await api(
      "/runs/" + selected.value + "/actions",
      "POST",
      {
        agentId: intervention.value.agentId,
        expectedVersion: state.value?.version,
        decision: {
          actionType: intervention.value.actionType,
          targetId: intervention.value.targetId,
          parameters: JSON.parse(intervention.value.parameters),
          reason: "User-requested simulated intervention",
        },
      },
      { "Idempotency-Key": crypto.randomUUID() },
    );
    await loadRun();
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function executeObjectAction(input: ObjectActionInput) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await api("/runs/" + selected.value + "/actions", "POST", input, { "Idempotency-Key": crypto.randomUUID() });
    await loadRun();
    await listRuns();
  } catch (e) { error.value = String(e); }
  finally { busy.value = false; }
}
function selectEntity(id: string) {
  selectedEntityId.value = id;
}
async function openDeletion(event: MouseEvent) {
  if (!selected.value || busy.value) return;
  deletionTrigger = event.currentTarget as HTMLElement;
  deletionId.value = selected.value;
  deletionError.value = "";
  await nextTick();
  cancelButton.value?.focus();
}
async function closeDeletion() {
  if (busy.value) return;
  deletionId.value = null;
  await nextTick();
  deletionTrigger?.focus();
}
function dialogKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") { event.preventDefault(); void closeDeletion(); }
  if (event.key === "Tab") {
    event.preventDefault();
    const buttons = Array.from(dialog.value?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)") ?? []);
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
    if (buttons.length) buttons[(index + (event.shiftKey ? -1 : 1) + buttons.length) % buttons.length]?.focus();
  }
}
async function deleteRun() {
  if (!deletionId.value || busy.value) return;
  busy.value = true;
  deletionError.value = "";
  const id = deletionId.value;
  try {
    await api<void>("/runs/" + id, "DELETE");
  } catch (e) {
    deletionError.value = e instanceof ApiError && e.code === "RUN_HAS_BRANCHES" ? t.value.hasBranches
      : e instanceof ApiError && e.code === "RUN_ACTIVE" ? t.value.activeRun : String(e);
    busy.value = false;
    await nextTick();
    cancelButton.value?.focus();
    return;
  }
  // Clear the deleted view immediately, even if refreshing the catalog fails.
  runs.value = runs.value.filter(run => run.id !== id);
  delete branchLabels.value[id];
  if (other.value === id) other.value = "";
  selected.value = "";
  await loadRun();
  deletionId.value = null;
  try {
    await listRuns();
    selected.value = runs.value[0]?.id ?? "";
    await loadRun();
  } catch (e) { error.value = String(e); }
  finally {
    busy.value = false;
    await nextTick();
    runSelect.value?.focus();
  }
}
onMounted(async () => {
  try {
    await listRuns();
    if (!runs.value.some(run => run.id === selected.value)) selected.value = runs.value[0]?.id ?? "";
    await loadRun();
  } catch (e) {
    error.value = String(e);
  }
});
onBeforeUnmount(() => {
  disposed = true;
  revision++;
  seekRevision++;
  source?.close();
  stopPlayback();
});
</script>
<template>
  <section :inert="deletionId !== null ? true : undefined" class="space-y-5">
    <div class="panel">
      <div class="flex flex-wrap items-end gap-4">
        <h2 class="mr-auto text-xl font-semibold">{{ t.title }}</h2>
        <label class="min-w-64"
          >{{ t.run
          }}<select ref="runSelect" :disabled="busy" :aria-label="t.run" v-model="selected" @change="loadRun">
            <option v-for="run in runs" :key="run.id" :value="run.id">
              {{ branchLabels[run.id] ?? run.id.slice(0, 8) }} ·
              {{ runStatus(run) }}
            </option>
          </select></label
        ><button class="secondary" :disabled="busy" @click="loadRun">
          {{ t.refresh }}
        </button>
        <button class="secondary text-red-700" :disabled="busy || !selected" @click="openDeletion">{{ t.remove }}</button>
      </div>
      <p class="mt-4 text-xs text-amber-800">{{ t.notice }}</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="!selected" class="mt-5">{{ t.none }}</p>
      <div v-if="info" class="mt-5 flex flex-wrap items-center gap-3">
        <span data-testid="run-status" class="rounded-md bg-slate-100 px-3 py-2 text-sm">{{
          runStatus(info)
        }}</span
        ><button
          class="primary"
          :disabled="busy || terminal || info.status !== 'PAUSED'"
          @click="command('step')"
        >
          {{ t.compute }}</button
        ><button
          class="secondary"
          :disabled="busy || terminal || info.status !== 'PAUSED'"
          @click="command('resume')"
        >
          {{ t.resume }}</button
        ><button
          class="secondary"
          :disabled="busy || info.status !== 'RUNNING'"
          @click="command('pause')"
        >
          {{ t.pause }}</button
        ><button
          class="secondary"
          :disabled="busy || terminal"
          @click="command('stop')"
        >
          {{ t.stop }}</button
        ><span class="ml-auto text-xs text-slate-500" role="status">{{
          connection
        }}</span>
      </div>
      <p v-if="info?.activeRuntimeMs !== undefined" data-testid="active-runtime" class="mt-3 text-xs text-slate-500">
        {{ lang === 'zh' ? '累计实际推演耗时' : 'Accumulated active computation time' }}：{{ (info.activeRuntimeMs / 1000).toFixed(2) }} {{ lang === 'zh' ? '秒（不含暂停等待）' : 'seconds (paused waiting excluded)' }}
      </p>
      <div v-if="terminationSummary" data-testid="termination-summary" class="mt-3 rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm">
        <p>{{ lang === 'zh' ? '结束原因' : 'Termination reason' }}：{{ terminationSummary }}</p>
        <p v-if="info?.terminationReason" class="mt-1 text-xs text-slate-500">{{ info.terminationReason }}</p>
        <p v-if="info?.terminationReason && terminationEvent?.payload.actual !== undefined" class="mt-1 text-xs">
          {{ lang === 'zh' ? '触发值 / 限额' : 'Trigger value / limit' }}：{{ terminationEvent.payload.actual }} / {{ terminationEvent.payload.limit }}{{ info.terminationReason === 'MAX_RUNTIME' ? (lang === 'zh' ? ' 毫秒' : ' ms') : '' }}
        </p>
      </div>
    </div>
    <template v-if="state && info">
      <ObjectiveResult v-if="info.objective && info.id === selected" :objective="info.objective" :max-steps="info.maxSteps"
        :events="shownEvents" :titles="eventDescriptions" :lang="lang" @evidence="showObjectiveEvidence" />
      <div class="min-w-0">
        <article class="panel min-w-0" :aria-label="lang === 'zh' ? '关联图' : 'Relationship graph'">
          <div class="flex flex-wrap justify-between gap-3 text-sm">
            <span data-testid="world-version"
              >{{ t.version }}: {{ state.version }}</span
            ><span>{{ t.time }}: {{ state.simulationTime }}</span
            ><button class="text-blue-700" @click="loadRun">
              {{ live ? t.live : t.history }}
            </button>
          </div>
          <Graph
            v-if="graphWorld"
            :world="graphWorld"
            :lang="lang"
            :highlight-id="highlight"
            :baseline-ids="baselineIds"
            @select="selectEntity"
          />
          <div class="border-t border-slate-100 pt-4">
            <h3 class="font-semibold">{{ t.object }}</h3>
            <EntityDetails v-if="selectedEntity" :entity="selectedEntity" :lang="lang" :catalog="actionCatalog" :live="live" />
            <p v-else class="mt-2 text-sm text-slate-500">{{ t.noObject }}</p>
          </div>
        </article>
      </div>
      <HierarchyTrace :tasks="tasks" :events="events" :agents="agents" :cursor="cursor" :lang="lang" :entities="state.entities" :catalog="actionCatalog" :hide-outcome="!!info.objective" />
      <div class="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_340px]">
      <article class="panel min-w-0" :aria-label="t.events">
        <h3 class="mb-4 font-semibold">{{ t.events }}</h3>
        <div class="flex flex-wrap items-center gap-3">
          <button
            class="secondary"
            :disabled="cursor === 0"
            @click="seek(cursor - 1)"
          >
            {{ t.prev }}</button
          ><button class="primary" :disabled="maxSequence === 0" @click="play">
            {{ playing ? t.pauseReplay : t.play }}</button
          ><button
            class="secondary"
            :disabled="cursor >= maxSequence"
            @click="seek(cursor + 1)"
          >
            {{ t.next }}</button
          ><label class="ml-auto"
            >{{ t.speed
            }}<select :aria-label="t.speed" v-model.number="speed">
              <option :value="1">1x</option>
              <option :value="2">2x</option>
              <option :value="4">4x</option>
            </select></label
          >
        </div>
        <label class="mt-4"
          >{{ t.position }}: {{ cursor }} / {{ maxSequence
          }}<input
            type="range"
            min="0"
            :max="maxSequence"
            :value="cursor"
            @input="seek(Number(($event.target as HTMLInputElement).value))"
        /></label>
        <label class="mt-4 block text-sm">{{ lang === 'zh' ? '事件范围' : 'Event scope' }}<select v-model="eventScope"><option value="key">{{ lang === 'zh' ? '关键事件（行动结果、状态变化与运行状态）' : 'Key events (outcomes, changes and lifecycle)' }}</option><option value="all">{{ lang === 'zh' ? '全部事件（含观察、决策和工具查询）' : 'All events (including observations, decisions and queries)' }}</option></select></label>
        <p class="mt-2 text-xs text-slate-500">{{ lang === 'zh' ? '列表筛选不改变回放位置；上一事件、下一事件仍按原始事件顺序浏览。' : 'Filtering does not change the playback position. Previous/next still follow the original event order.' }}</p>
        <div class="mt-4 grid max-h-96 gap-2 overflow-auto" :aria-label="lang === 'zh' ? '事件列表' : 'Event list'">
          <button
            v-for="event in timelineEvents"
            :key="event.sequence"
            class="rounded-lg border px-3 py-3 text-left text-sm"
            :aria-pressed="event.sequence === cursor"
            :class="
              event.sequence === cursor
                ? 'border-blue-600 bg-blue-50'
                : 'border-slate-200'
            "
            @click="seek(event.sequence)"
          >
            <span class="flex flex-wrap items-center gap-2 font-medium"><span>#{{ event.sequence }} {{ eventDescriptions.get(event.sequence)?.title }}</span><span class="text-xs font-normal text-slate-500">{{ lang === 'zh' ? '模拟时间' : 'Simulation time' }} {{ event.simulationTime }} · {{ eventDescriptions.get(event.sequence)?.actor }}</span></span>
            <span class="mt-1 block break-words text-xs text-slate-600">{{ eventDescriptions.get(event.sequence)?.summary }}</span>
          </button>
          <p v-if="!timelineEvents.length" class="text-sm text-slate-500">{{ lang === 'zh' ? '当前筛选下暂无事件。' : 'No events match this filter.' }}</p>
        </div>
        <div v-if="latest" ref="eventDetailsTarget" class="mt-4">
          <EventDetails data-testid="selected-event-details" :event="latest" :context="eventContext" :lang="lang" />
        </div>
      </article>
        <aside class="panel min-w-0 self-start xl:max-h-[780px] xl:overflow-auto" :aria-label="t.agents">
          <h3 class="mb-4 font-semibold">{{ t.agents }}</h3>
          <article
            v-for="agent in agents"
            :key="agent.id"
            class="mb-5 border-b border-slate-100 pb-4"
          >
            <h4 class="font-medium">{{ agentDisplayName(agent, lang) }}</h4>
            <span class="text-xs text-amber-700">{{
              modelModeLabel([...shownEvents]
                .reverse()
                .find(
                  (e) =>
                    e.agentId === agent.id && e.eventType === "AGENT_STARTED",
                )?.payload.modelMode, lang)
            }}</span>
            <p class="my-2 text-xs text-slate-500">{{ agentGoalDescription(agent.goal, lang) }}</p>
            <details v-if="agentDisplayName(agent, lang) !== agent.name || agentGoalDescription(agent.goal, lang) !== agent.goal" class="mb-3 text-xs text-slate-500"><summary class="cursor-pointer">{{ lang === 'zh' ? '原始名称与任务目标' : 'Original name and goal' }}</summary><p class="mt-2 break-words">{{ agent.name }}</p><p class="mt-1 whitespace-pre-wrap break-words">{{ agent.goal }}</p></details>
            <details
              v-for="event in shownEvents
                .filter(
                  (e) =>
                    e.agentId === agent.id &&
                    [
                      'AGENT_OBSERVATION',
                      'AGENT_DECISION',
                      'AGENT_TOOL_CALL',
                      'ACTION_ACCEPTED',
                      'ACTION_REJECTED',
                    ].includes(e.eventType),
                )
                .slice(-5)"
              :key="event.sequence"
              class="my-2"
            >
              <summary
                class="cursor-pointer text-xs"
                :class="
                  event.eventType === 'ACTION_REJECTED'
                    ? 'text-red-700'
                    : 'text-slate-700'
                "
              >
                #{{ event.sequence }} {{ eventDescriptions.get(event.sequence)?.title }}
              </summary>
              <EventDetails class="mt-2" :event="event" :context="eventContext" :lang="lang" />
            </details>
          </article>
        </aside>
      </div>
      <div class="grid gap-5 lg:grid-cols-2">
        <form class="panel grid gap-4" @submit.prevent="fork">
          <h3 class="font-semibold">{{ t.branch }}</h3>
          <label
            >{{ t.label
            }}<input v-model="label" required maxlength="100" /></label
          ><button class="primary" :disabled="busy">
            {{ t.fork }} #{{ cursor }}
          </button>
        </form>
        <form class="panel grid gap-4" @submit.prevent="compare">
          <label
            >{{ t.other
            }}<select :aria-label="t.other" v-model="other" required>
              <option
                v-for="run in runs.filter((r) => r.id !== selected)"
                :key="run.id"
                :value="run.id"
              >
                {{ branchLabels[run.id] ?? run.id.slice(0, 8) }} ·
                {{ runStatus(run) }}
              </option>
            </select></label
          ><button class="primary" :disabled="busy || !other">
            {{ t.compare }}
          </button>
        </form>
      </div>
      <ObjectActions v-if="info.status === 'PAUSED' && live && actionCatalog?.agents"
        :catalog="actionCatalog" :agents="agents" :lang="lang" :busy="busy" :target-id="selectedEntity?.id" @submit="executeObjectAction" />
      <form
        v-if="info.status === 'PAUSED' && live && !actionCatalog?.agents"
        class="panel grid gap-4"
        @submit.prevent="intervene"
      >
        <h3 class="font-semibold">{{ t.intervention }}</h3>
        <div class="grid gap-4 md:grid-cols-3">
          <label
            >{{ t.actor
            }}<select
              v-model="intervention.agentId"
              :aria-label="t.actor"
              @change="intervention.actionType = allowed[0] ?? ''"
            >
              <option v-for="agent in agents" :key="agent.id" :value="agent.id">
                {{ agentDisplayName(agent, lang) }}
              </option>
            </select></label
          ><label
            >{{ t.action
            }}<select :aria-label="t.action" v-model="intervention.actionType">
              <option v-for="action in allowed" :key="action">
                {{ action }}
              </option>
            </select></label
          ><label
            >{{ t.target }}<input v-model="intervention.targetId"
          /></label>
        </div>
        <label
          >{{ t.params
          }}<textarea
            v-model="intervention.parameters"
            rows="3"
            required
          /></label
        ><button class="secondary justify-self-start" :disabled="busy">
          {{ t.submit }}
        </button>
      </form>
      <Comparison v-if="comparison" :result="comparison" :lang="lang" />
    </template>
  </section>
  <Teleport to="body">
    <div v-if="deletionId !== null" class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-5" @click.self="closeDeletion">
      <div ref="dialog" role="dialog" aria-modal="true" aria-labelledby="run-delete-title" aria-describedby="run-delete-description" class="panel w-full max-w-md" @keydown="dialogKeydown">
        <h2 id="run-delete-title" class="text-lg font-semibold">{{ t.confirm }}</h2>
        <p class="mt-3 break-all font-mono text-xs">{{ branchLabels[deletionId] ?? deletionId }}</p>
        <p id="run-delete-description" class="mt-3 text-sm text-slate-600">{{ t.deleteHelp }}</p>
        <p v-if="deletionError" role="alert" class="mt-3">{{ deletionError }}</p>
        <div class="mt-6 flex justify-end gap-3">
          <button class="secondary text-red-700" :disabled="busy" @click="deleteRun">{{ t.confirm }}</button>
          <button ref="cancelButton" class="secondary" :disabled="busy" @click="closeDeletion">{{ t.cancel }}</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

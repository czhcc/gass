<script setup lang="ts">
import {computed} from 'vue';
import type {ActionCatalog,Agent,BusinessOutcome,Entity,Lang,RunEvent,TaskRecord} from '../types';
import {agentDisplayName} from '../agentPresentation';
import {executionSummary,publicNarrative} from '../taskNarrative';
const props=defineProps<{tasks:TaskRecord[];events:RunEvent[];agents:Agent[];cursor:number;lang:Lang;entities?:Entity[];catalog?:ActionCatalog}>();
const zh=computed(()=>props.lang==='zh');
const visible=computed(()=>props.tasks.filter(t=>t.createdSequence<=props.cursor));
const roots=computed(()=>visible.value.filter(t=>!t.parentTaskId));
const completed=(t:TaskRecord)=>t.completedSequence!==null&&t.completedSequence<=props.cursor;
const result=(t:TaskRecord)=>completed(t)?t.result:{};
const report=(t:TaskRecord)=>result(t).report as {summary?:string;findings?:string[];nextTasks?:string[]}|undefined;
const receipts=(t:TaskRecord)=>(result(t).receipts??[]) as {accepted:boolean;actionType:string;targetId:string;inputVersion:number;outputVersion:number;code:string}[];
const queries=(t:TaskRecord)=>(result(t).queryResults??[]) as {version:number;totalObjects:number;totalRelations:number;truncated:boolean}[];
const name=(id:string)=>agentDisplayName(props.agents.find(a=>a.id===id)??{id},props.lang);
const objectName=(id:string)=>props.entities?.find(e=>e.id===id)?.name??id;
const actionName=(id:string)=>{const definition=props.catalog?.agents.flatMap(a=>a.capabilities).find(c=>c.action.actionType===id)?.action;return definition?(zh.value?definition.nameZh:definition.nameEn):id;};
const status=(t:TaskRecord)=>!completed(t)?(zh.value?'执行中':'Running'):({SUCCEEDED:zh.value?'已完成':'Completed',REJECTED:zh.value?'被拒绝':'Rejected',FAILED:zh.value?'执行失败':'Failed',CANCELLED:zh.value?'已取消':'Cancelled',NO_ACTION:zh.value?'未执行操作':'No Action submitted',NO_EVIDENCE:zh.value?'未返回查询证据':'No query evidence'}[t.status]??t.status);
const outcome=computed(()=>[...props.events].reverse().find(e=>e.sequence<=props.cursor&&e.eventType==='SCENARIO_EVALUATED')?.payload.outcome as BusinessOutcome|undefined);
const children=(root:TaskRecord)=>visible.value.filter(t=>t.parentTaskId===root.taskId);
const narrative=(value:unknown)=>publicNarrative(value,props.lang);
const findings=(root:TaskRecord)=>(report(root)?.findings??[]).map(narrative).filter(Boolean);
const nextTasks=(root:TaskRecord)=>(report(root)?.nextTasks??[]).map(narrative).filter(Boolean);
const summary=(root:TaskRecord)=>narrative(report(root)?.summary)||(zh.value?`本轮委派 ${children(root).length} 项子任务，${children(root).filter(completed).length} 项已返回；`:`${children(root).length} subtasks delegated; `)+executionSummary(children(root).filter(completed).map(result),props.lang);
const taskGoal=(task:TaskRecord)=>narrative(task.request.goal)||(props.agents.find(a=>a.id===task.agentId)?.runtime?.role==='QUERY'?(zh.value?'查询关联对象并分析图谱':'Query and analyze the graph'):(zh.value?'执行授权对象操作':'Perform authorized object Actions'));
</script>
<template>
  <section v-if="roots.length||outcome" class="panel min-w-0 [overflow-wrap:anywhere]" role="region" :aria-label="zh?'主从任务与研判':'Delegated tasks and review'">
    <h3 class="font-semibold">{{zh?'主从任务与研判':'Delegated tasks and review'}}</h3>
    <p class="mt-2 text-xs text-slate-500">{{zh?'展示任务、查询证据和引擎回执。任务已完成不等于场景效果已达成。':'Shows tasks, query evidence and engine receipts. Completed tasks do not imply successful business effects.'}}</p>
    <div v-if="outcome" class="mt-3 rounded border p-3" :class="outcome.result==='SUCCEEDED'?'border-emerald-300 bg-emerald-50':'border-amber-300 bg-amber-50'">
      <p class="font-medium">{{zh?'场景效果':'Business effects'}}：{{outcome.result==='SUCCEEDED'?(zh?'已达成':'Achieved'):outcome.result==='ERROR'?(zh?'判定出错':'Evaluation error'):(zh?'未达成':'Not achieved')}} · {{zh?'评价轮次':'Evaluation round'}} {{outcome.evaluatedRound}}</p>
      <ul class="mt-2 list-inside list-disc text-sm"><li v-for="condition in outcome.conditions" :key="condition.id">{{condition.satisfied?'✓':'✗'}} {{zh?condition.nameZh:condition.nameEn}}<span v-if="condition.error"> · {{zh?'条件计算失败，请检查定义':'Condition failed to evaluate; check its definition'}}</span></li></ul>
    </div>
    <div class="mt-4 max-h-[520px] space-y-3 overflow-auto">
      <article v-for="root in roots" :key="root.taskId" class="min-w-0 rounded border border-slate-200 p-3">
        <h4 class="text-sm font-semibold">{{zh?'第':'Round'}} {{root.round}} {{zh?'轮 · ': ' · '}}{{name(root.agentId)}} · {{status(root)}}</h4>
        <p v-if="completed(root)" class="mt-2 whitespace-pre-line text-sm">{{summary(root)}}</p>
        <p v-if="completed(root)&&report(root)?.summary&&!narrative(report(root)?.summary)" class="mt-1 text-xs text-slate-500">{{zh?'以上为执行记录摘要，原始研判可展开查看。':'The summary above uses execution records; expand the original review below.'}}</p>
        <ul v-if="findings(root).length" class="mt-2 list-inside list-disc space-y-1 text-xs text-slate-600"><li v-for="finding in findings(root)" :key="finding">{{finding}}</li></ul>
        <div v-if="nextTasks(root).length" class="mt-2 text-xs"><p class="font-medium">{{zh?'下一步安排':'Next tasks'}}</p><ul class="mt-1 list-inside list-disc space-y-1"><li v-for="task in nextTasks(root)" :key="task">{{task}}</li></ul></div>
        <details v-if="report(root)" data-original-report class="mt-2 text-xs text-slate-500"><summary class="cursor-pointer">{{zh?'原始研判与计划':'Original review and plan'}}</summary><pre class="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-all">{{JSON.stringify(report(root),null,2)}}</pre></details>
        <div class="mt-3 space-y-2 border-l-2 border-blue-200 pl-3">
          <details v-for="task in visible.filter(t=>t.parentTaskId===root.taskId)" :key="task.taskId" class="rounded bg-slate-50 p-2 text-sm">
            <summary class="cursor-pointer whitespace-normal">{{name(task.agentId)}} · {{status(task)}} · {{taskGoal(task)}}</summary>
            <p class="mt-2 text-xs">{{zh?'状态版本':'State version'}} {{task.inputVersion}} → {{completed(task)?task.outputVersion:'…'}}</p>
            <p v-if="completed(task)" class="mt-2 whitespace-pre-line text-xs">{{narrative(result(task).summary)||executionSummary([result(task)],lang)}}</p>
            <p v-for="(query,i) in queries(task)" :key="'q'+i" class="mt-2 text-xs">{{zh?'查询证据':'Query evidence'}} · v{{query.version}} · {{query.totalObjects}} {{zh?'个对象':'objects'}} / {{query.totalRelations}} {{zh?'条关系':'relationships'}}{{query.truncated?(zh?'（已分页，非完整图谱）':' (paged projection)'):''}}</p>
            <p v-for="(receipt,i) in receipts(task)" :key="'r'+i" class="mt-2 text-xs">{{receipt.accepted?(zh?'引擎已接受':'Engine accepted'):(zh?'引擎已拒绝':'Engine rejected')}} · {{actionName(receipt.actionType)}} · {{objectName(receipt.targetId)}} · v{{receipt.inputVersion}} → v{{receipt.outputVersion}}</p>
            <details v-if="completed(task)" class="mt-2 text-xs"><summary>{{zh?'原始任务与结果':'Raw task and result'}}</summary><p class="mt-2 whitespace-pre-wrap">{{task.request.goal}}</p><pre class="mt-2 max-h-52 overflow-auto whitespace-pre-wrap break-all">{{JSON.stringify(task.result,null,2)}}</pre></details>
          </details>
        </div>
      </article>
    </div>
  </section>
</template>

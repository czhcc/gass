import type { ActionCatalog, ActionDefinition, Agent, Entity, Lang, Relation, RunEvent } from './types';
import { unavailableDescription } from './actionDescriptions';
import { agentDisplayName, modelModeLabel } from './agentPresentation';
import { executionSummary, publicNarrative } from './taskNarrative';

export interface EventContext { events: RunEvent[]; agents?: Agent[]; entities?: Entity[]; relations?: Relation[]; catalog?: ActionCatalog }
export interface EventDescription {
  title: string; summary: string; actor: string; tone: 'neutral' | 'success' | 'warning' | 'danger';
  facts: { label: string; value: string }[]; changes: string[]; reasoning: string; originalReason: string;
}
type Data = Record<string, unknown>;
const object = (v: unknown): Data => v !== null && typeof v === 'object' && !Array.isArray(v) ? v as Data : {};
const objects = (v: unknown): Data[] => Array.isArray(v) ? v.filter(x => x !== null && typeof x === 'object') : [];
const str = (v: unknown) => typeof v === 'string' || typeof v === 'number' ? String(v) : '';
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
const choose = (lang: Lang, zh: string, en: string) => lang === 'zh' ? zh : en;
export const terminationMessages: Record<string, [string, string]> = {
  ALL_AGENTS_ENDED: ['所有 Agent 均已成功结束任务', 'All agents submitted accepted END decisions'],
  MAX_STEPS: ['推演轮数达到上限', 'Simulation round limit reached'],
  MAX_EVENTS: ['事件数量达到预算限制（预留 10 条审计事件）', 'Event limit reached (10 audit events reserved)'],
  MAX_RUNTIME: ['实际推演耗时达到上限', 'Active computation time limit reached'],
  MAX_AGENT_DECISIONS: ['尚未结束的 Agent 均已耗尽各自的决策次数', 'All remaining agents exhausted their decision limits'],
  MAX_DECISIONS: ['运行总决策次数达到上限', 'Global decision limit reached'],
  MODEL_REQUEST_BUDGET: ['模型请求预算不足以预留下一次决策（按预估请求数计算）', 'Insufficient request budget for the next decision (reserved estimate)'],
  TOKEN_BUDGET: ['输出 Token 预算不足以预留下一次决策（按预估用量计算）', 'Insufficient token budget for the next decision (reserved estimate)'],
  MAX_STATE_CHANGES: ['状态变更次数达到上限', 'State change limit reached'],
  USER_STOPPED: ['用户手动停止运行', 'Stopped by user'],
  MODEL_DECISION_FAILED: ['模型决策失败或超时，请检查模型配置', 'Model decision failed or timed out; verify model configuration'],
  OBJECTIVE_MET: ['已到达设定轮数，场景效果已达成', 'Configured horizon reached; business effects achieved'],
  OBJECTIVE_NOT_MET: ['已到达设定轮数，场景效果未达成', 'Configured horizon reached; business effects not achieved'],
  OBJECTIVE_EVALUATION_FAILED: ['效果条件计算失败，请检查场景配置', 'Outcome conditions failed to evaluate; check the scenario configuration'],
  OBJECTIVE_NOT_VERIFIABLE: ['已到达设定轮数，未配置可判定条件', 'Configured horizon reached without engine conditions'],
  OBJECTIVE_HORIZON_INTERRUPTED: ['截止轮次中断，未完成目标判定', 'The final round was interrupted before evaluation'],
};
export const failureDiagnosticMessages: Record<string, [string, string]> = {
  MODEL_REQUEST_TIMEOUT: ['模型请求超时', 'Model request timed out'],
  MASTER_REPORT_INVALID: ['主 Agent 的结构化报告无效或缺失', 'Master Agent returned an invalid or missing structured report'],
  HIERARCHICAL_EXECUTION_ERROR: ['主从推演执行异常，请查看任务记录', 'Hierarchical execution failed; inspect the task records'],
};
const titles: Record<string, [string, string]> = {
  RUN_STARTED: ['运行已创建', 'Run created'], RUN_RESUMED: ['开始自动推演', 'Computation resumed'], RUN_PAUSED: ['推演已暂停', 'Computation paused'], RUN_STOPPED: ['运行已停止', 'Run stopped'],
  RUN_COMPLETED: ['运行已结束', 'Run ended'], RUN_FAILED: ['推演发生故障', 'Computation failed'], RUN_BUDGET_EXHAUSTED: ['推演预算耗尽', 'Budget exhausted'],
  AGENT_STARTED: ['Agent 开始决策', 'Agent started a decision'], AGENT_OBSERVATION: ['Agent 获取观察', 'Agent observation'], AGENT_DECISION: ['Agent 作出决策', 'Agent decision'],
  AGENT_TOOL_CALL: ['Agent 查询图谱', 'Agent graph query'], USER_INTERVENTION: ['用户提出干预', 'User intervention'], ACTION_PROPOSED: ['行动等待校验', 'Action awaiting validation'],
  ACTION_ACCEPTED: ['行动已接受', 'Action accepted'], ACTION_REJECTED: ['行动被拒绝', 'Action rejected'], WORLD_STATE_CHANGED: ['图谱状态已更新', 'Graph state updated'],
  SIMULATION_EVENT_SCHEDULED: ['已安排输入事件', 'Input event scheduled'], SIMULATION_EVENT_CREATED: ['输入事件已送达', 'Input event delivered'],
  BRANCH_CREATED: ['已创建运行分支', 'Branch created'], AGENT_MEMORY_RESTORED: ['Agent 历史已恢复', 'Agent history restored'],
  MASTER_TASK_STARTED: ['主 Agent 开始研判', 'Master started planning'], MASTER_TASK_COMPLETED: ['主 Agent 本轮任务结束', 'Master cycle ended'],
  SUBTASK_DISPATCHED: ['已委派子任务', 'Subtask delegated'], SUBTASK_COMPLETED: ['子任务已返回', 'Subtask returned'],
  MASTER_REVIEW: ['主 Agent 汇总研判', 'Master review'], GRAPH_QUERY_RESULT: ['子 Agent 查询证据', 'Worker query evidence'],
  ACTION_TASK_RECEIPT: ['子任务执行回执', 'Task Action receipt'], ACTION_INPUT_HANDLED: ['输入请求已处理', 'Input request handled'],
  TASK_MEMORY_RESTORED: ['已继承任务记忆', 'Task memory inherited'], SCENARIO_EVALUATED: ['场景效果判定', 'Business effects evaluated'], MODEL_REQUESTED: ['已请求模型', 'Model requested'],
  OBJECTIVE_REVIEWED: ['主研判目标结论', 'Master objective review'],
};
const actions: Record<string, [string, string]> = {
  MARK_REVIEWED: ['标记审查', 'Mark reviewed'], ANALYZE_RELATIONS: ['分析关系', 'Analyze relations'], SIMULATE_PROPAGATION: ['模拟接收传播', 'Simulate exposure'],
  REVOKE_SHARING: ['撤销共享', 'Revoke sharing'], DISABLE_SYNC: ['禁用同步', 'Disable synchronization'], TRANSFER: ['模拟转账', 'Simulated transfer'],
  FLAG_RISK: ['标记模拟风险', 'Flag simulated risk'], ANALYZE_ACCOUNT: ['分析账户', 'Analyze account'], ASSESS_RISK_CEL: ['CEL 风险评分', 'CEL risk score'], END: ['结束 Agent 任务', 'End agent task'],
};
const rejectionReasons: Record<string, [string, string]> = {
  ACTION_FORBIDDEN: ['此 Agent 没有该行动的执行权限', 'The agent is not permitted to perform this action'],
  ACTION_TARGET_HIDDEN: ['目标或关联对象超出 Agent 的可见范围', 'The owner or related object is outside the agent’s visible scope'],
  ACTION_TARGET_INVALID: ['行动所属对象或目标类型不符合要求', 'The owner object or target type is invalid'],
  ACTION_NOT_BOUND: ['该对象类型未定义此行动', 'This action is not bound to the object type'],
  ACTION_PARAMETERS_INVALID: ['参数不符合行动定义，请检查必填项、类型或金额精度', 'Parameters do not match the action schema; check required fields, types and amount precision'],
  ACTION_PRECONDITION_FAILED: ['当前状态不满足行动前置条件', 'The current state does not satisfy the action preconditions'],
  ACTION_PROGRAM_INVALID: ['行动定义或 CEL 计算未通过校验', 'The action definition or CEL evaluation is invalid'],
};
const parameters: Record<string, [string, string]> = { sourceId: ['来源对象', 'Source object'], recipientId: ['共享接收对象', 'Sharing recipient'], destinationId: ['同步目标对象', 'Sync destination'], amount: ['金额（元）', 'Amount (major units)'], currency: ['币种', 'Currency'] };
const properties: Record<string, [string, string]> = { reviewed: ['审查状态', 'Review status'], exposed: ['模拟暴露状态', 'Simulated exposure'], riskFlag: ['模拟风险标记', 'Simulated risk flag'], riskScore: ['模拟风险评分', 'Simulated risk score'], balanceMinor: ['账户余额', 'Account balance'], currency: ['币种', 'Currency'], exposureSource: ['传播来源', 'Exposure source'], sourceId: ['转出账户', 'Source account'], targetId: ['转入账户', 'Destination account'], amountMinor: ['金额（分）', 'Amount (minor units)'] };
const relations: Record<string, [string, string]> = { SHARED_WITH: ['共享', 'Sharing'], SYNCS_TO: ['同步', 'Synchronization'], ACCESSED_BY: ['访问', 'Access'], STORED_IN: ['存储', 'Storage'], CONTACTS: ['联系', 'Contact'] };
const tools: Record<string, [string, string]> = { graph_search_entity: ['搜索可见对象', 'Search visible objects'], graph_get_entity: ['读取可见对象', 'Read a visible object'], graph_expand_relations: ['查询关系可达范围', 'Expand visible relationships'], graph_find_paths: ['查询图谱路径', 'Find graph paths'], graph_calculate_centrality: ['计算图谱中心性', 'Calculate centrality'], graph_get_subgraph: ['读取局部图谱', 'Read a subgraph'], graph_estimate_impact: ['估计潜在影响范围', 'Estimate potential impact'] };

export function isKeyEvent(event: RunEvent) {
  return event.eventType.startsWith('RUN_') || ['ACTION_ACCEPTED', 'ACTION_REJECTED', 'WORLD_STATE_CHANGED', 'SIMULATION_EVENT_CREATED', 'SIMULATION_EVENT_SCHEDULED', 'BRANCH_CREATED', 'MASTER_REVIEW', 'SUBTASK_DISPATCHED', 'SUBTASK_COMPLETED', 'SCENARIO_EVALUATED', 'OBJECTIVE_REVIEWED'].includes(event.eventType);
}

/** Uses persisted proposals and snapshots only; it never translates by calling a model. */
export function describeEvent(event: RunEvent, context: EventContext, lang: Lang): EventDescription {
  const t = (zh: string, en: string) => choose(lang, zh, en);
  const local = (dictionary: Record<string, [string, string]>, key: string) => dictionary[key]?.[lang === 'zh' ? 0 : 1] ?? key;
  const p = object(event.payload), before = object(p.before), after = object(p.after), observation = object(p.observation);
  const pool = [...objects(after.entities), ...objects(before.entities), ...objects(observation.entities), ...(context.entities ?? [])];
  const name = (id: unknown) => (pool.find(e => e.id === id)?.name as string | undefined) ?? (str(id) || t('未指定对象', 'Unspecified object'));
  const agent = context.agents?.find(a => a.id === event.agentId);
  const actor = event.agentId ? agentDisplayName(agent ?? { id: event.agentId }, lang) : t('系统', 'System');
  const result: EventDescription = { title: titles[event.eventType] ? local(titles, event.eventType) : t(`其他事件（${event.eventType}）`, `Other event (${event.eventType})`), summary: '', actor, tone: 'neutral', facts: [], changes: [], reasoning: '', originalReason: '' };
  const fact = (label: string, value: unknown) => { if (value !== undefined && value !== null && value !== '') result.facts.push({ label, value: str(value) || JSON.stringify(value) }); };
  let decision = object(p.decision ?? p.action);
  if (['ACTION_ACCEPTED', 'ACTION_REJECTED'].includes(event.eventType)) {
    const matching = context.events.filter(e => e.runId === event.runId && e.sequence === event.sequence - 1 && e.agentId === event.agentId && e.simulationTime === event.simulationTime && e.eventType === 'ACTION_PROPOSED')
      .sort((a, b) => b.sequence - a.sequence).find(e => { const d = object(e.payload.action); return d.actionType === p.actionType && d.targetId === p.targetId; });
    decision = { ...object(matching?.payload.action), ...p };
    result.reasoning = str(object(matching?.payload.action).reason);
  } else result.reasoning = str(decision.reason);
  const actionType = str(decision.actionType), targetId = str(decision.targetId), params = object(decision.parameters);
  const observed = context.events.filter(e => e.sequence <= event.sequence && e.agentId === event.agentId && e.eventType === 'AGENT_OBSERVATION').sort((a, b) => b.sequence - a.sequence)[0];
  const definitions = [
    ...objects(object(observed?.payload.observation).availableActions).map(c => object(c.action) as unknown as ActionDefinition),
    ...(context.catalog?.agents ?? []).flatMap(a => a.capabilities.map(c => c.action)),
  ];
  const definition = definitions.find(a => a.actionType === actionType);
  const actionName = definition ? (lang === 'zh' ? definition.nameZh : definition.nameEn) : local(actions, actionType);
  if (actionType) {
    fact(t('行动', 'Action'), actionName);
    if (targetId) fact(t('所属对象', 'Owner object'), name(targetId));
    const fields = object(definition?.parametersSchema.properties);
    for (const [key, value] of Object.entries(params)) {
      const schema = object(fields[key]);
      const label = parameters[key] ? local(parameters, key) : str(schema.description) || key;
      fact(label, key.endsWith('Id') || schema.format === 'entity-id' ? name(value) : value);
    }
    let action = targetId ? `${actionName} · ${name(targetId)}` : actionName;
    if (actionType === 'REVOKE_SHARING' && params.recipientId) action = t(`${name(targetId)} 撤销向 ${name(params.recipientId)} 的共享`, `${name(targetId)} revokes sharing with ${name(params.recipientId)}`);
    if (actionType === 'DISABLE_SYNC' && params.destinationId) action = t(`${name(targetId)} 停止向 ${name(params.destinationId)} 同步`, `${name(targetId)} disables synchronization to ${name(params.destinationId)}`);
    if (actionType === 'SIMULATE_PROPAGATION' && params.sourceId) action = t(`${name(targetId)} 从 ${name(params.sourceId)} 模拟接收信息`, `${name(targetId)} simulates receiving information from ${name(params.sourceId)}`);
    if (actionType === 'TRANSFER' && params.sourceId) action = t(`${name(params.sourceId)} 向 ${name(targetId)} 模拟转账 ${str(params.amount)} ${str(params.currency)}`, `Simulated transfer from ${name(params.sourceId)} to ${name(targetId)}: ${str(params.amount)} ${str(params.currency)}`);
    if (event.eventType === 'ACTION_REJECTED') {
      result.tone = 'danger'; result.summary = t(`未执行：${action}。`, `Not executed: ${action}.`);
      const translated = unavailableDescription(str(p.reason), lang);
      const condition = definition?.program?.conditions.find(c => c.messageEn === p.reason);
      fact(t('拒绝原因', 'Rejection reason'), condition ? (lang === 'zh' ? condition.messageZh : condition.messageEn) : translated !== str(p.reason) ? translated : local(rejectionReasons, str(p.code)) || t('引擎未通过该行动的校验', 'The engine rejected this action'));
      result.originalReason = str(p.reason);
    } else if (event.eventType === 'ACTION_ACCEPTED') {
      result.tone = 'success';
      result.summary = (definition?.program ? definition.program.operations.length === 0 : ['ANALYZE_RELATIONS', 'ANALYZE_ACCOUNT'].includes(actionType))
        ? t(`已记录对 ${name(targetId)} 的只读分析，不修改对象或关系；可达不代表已传播。`, `Read-only analysis of ${name(targetId)} recorded; objects and relationships are unchanged. Reachability is not propagation evidence.`)
        : t(`引擎已接受：${action}。具体变更见对应的“图谱状态已更新”事件。`, `Engine accepted: ${action}. See the corresponding graph-state event for committed changes.`);
      if (actionType === 'END') result.summary = t('引擎已接受该 Agent 的结束请求；整个运行是否结束由运行规则另行判断。', 'The engine accepted this agent’s END request. Run termination is evaluated separately.');
    } else result.summary = t(`拟执行：${action}；待引擎校验，尚不能视为执行结果。`, `Proposed: ${action}; awaiting engine validation, not an execution result.`);
  }
  if (event.eventType === 'WORLD_STATE_CHANGED') {
    result.tone = 'success'; result.summary = t('以下变化来自该事件保存的前后快照。', 'Changes below come from this event’s persisted before/after snapshots.');
    if (before.version !== undefined && after.version !== undefined) fact(t('状态版本', 'State version'), `${before.version} → ${after.version}`);
    else fact(t('状态版本', 'State version'), p.version);
    const valueText = (value: unknown, key: string, entity: Data) => {
      if (value === undefined) return t('未设置', 'Not set');
      if (typeof value === 'boolean') return key === 'reviewed' ? (value ? t('已审查', 'Reviewed') : t('未审查', 'Not reviewed')) : (value ? t('是', 'Yes') : t('否', 'No'));
      if (key === 'balanceMinor' && typeof value === 'number' && Number.isSafeInteger(value)) return `${(value / 100).toFixed(2)} ${str(object(entity.properties).currency)}`.trim();
      if (key === 'exposureSource' || key.endsWith('Id')) return name(value);
      return (str(value) || JSON.stringify(value)).slice(0, 240);
    };
    const oldEntities = objects(before.entities), newEntities = objects(after.entities);
    for (const entity of newEntities) {
      const previous = oldEntities.find(e => e.id === entity.id);
      if (!previous) { result.changes.push(t(`新增对象：${str(entity.name) || str(entity.id)}`, `Object added: ${str(entity.name) || str(entity.id)}`)); continue; }
      const oldProps = object(previous.properties), newProps = object(entity.properties);
      for (const key of new Set([...Object.keys(oldProps), ...Object.keys(newProps)])) {
        if (key.toLowerCase().includes('evidence') || same(oldProps[key], newProps[key])) continue;
        result.changes.push(`${str(entity.name) || str(entity.id)} · ${local(properties, key)}：${valueText(oldProps[key], key, previous)} → ${valueText(newProps[key], key, entity)}`);
      }
    }
    for (const entity of oldEntities.filter(e => !newEntities.some(n => n.id === e.id))) result.changes.push(t(`移除对象：${str(entity.name) || str(entity.id)}`, `Object removed: ${str(entity.name) || str(entity.id)}`));
    for (const relation of objects(after.relations)) {
      const old = objects(before.relations).find(r => r.id === relation.id);
      const label = `${name(relation.sourceId)} → ${name(relation.targetId)}（${local(relations, str(relation.relationType))}）`;
      if (!old) result.changes.push(t(`新增关系：${label}`, `Relationship added: ${label}`));
      else if (old.enabled !== relation.enabled) result.changes.push(`${label}：${old.enabled ? t('已启用', 'Enabled') : t('已禁用', 'Disabled')} → ${relation.enabled ? t('已启用', 'Enabled') : t('已禁用', 'Disabled')}`);
      else if (!same(old, relation)) result.changes.push(t(`关系属性或证据已更新：${label}`, `Relationship properties or evidence updated: ${label}`));
    }
    for (const old of objects(before.relations).filter(r => !objects(after.relations).some(n => n.id === r.id))) result.changes.push(t(`移除关系：${str(old.id)}`, `Relationship removed: ${str(old.id)}`));
    if (!before.entities || !after.entities) result.changes.push(t('此事件未保存完整前后快照，无法从该记录还原具体变更。', 'This event has no complete before/after snapshots; detailed changes cannot be reconstructed.'));
    else if (!result.changes.length) result.changes.push(t('对象或关系的其他信息已更新，详见原始事件。', 'Other object or relationship information changed; see the original event.'));
  } else if (event.eventType.startsWith('RUN_')) {
    const code = str(p.reasonCode);
    result.summary = code ? local(terminationMessages, code) : t('已记录运行状态变化。', 'Run lifecycle transition recorded.');
    if (event.eventType === 'RUN_BUDGET_EXHAUSTED') result.tone = 'warning';
    if (event.eventType === 'RUN_FAILED') {
      result.tone = 'danger';
      const diagnostic = str(p.diagnosticCode);
      if (failureDiagnosticMessages[diagnostic]) result.summary = local(failureDiagnosticMessages, diagnostic);
      fact(t('异常类型', 'Exception type'), p.exceptionType);
    }
    if (event.eventType === 'RUN_COMPLETED' && !code) result.summary = t('历史记录显示运行已结束，未记录可核实的业务完成原因。', 'The legacy record indicates an ended run without a verifiable business-completion reason.');
    if (p.actual !== undefined || p.limit !== undefined) fact(t('触发值 / 限额', 'Trigger value / limit'), `${p.actual ?? '—'} / ${p.limit ?? '—'}`);
    if (typeof p.activeRuntimeMs === 'number') fact(t('实际推演耗时', 'Active computation time'), `${(p.activeRuntimeMs / 1000).toFixed(2)} ${t('秒', 's')}`);
  } else if (event.eventType === 'AGENT_STARTED') {
    result.summary = t('正在根据当前可见状态决定下一步行动。', 'Choosing the next action from the current visible state.');
    fact(t('决策模型', 'Decision model'), modelModeLabel(p.modelMode, lang));
  } else if (event.eventType === 'AGENT_OBSERVATION') {
    result.summary = t('Agent 获取了授权可见范围内的对象、关系及当前可用行动。', 'The agent received authorized visible objects, relationships and action capabilities.');
    if (Array.isArray(observation.entities)) fact(t('可见对象数', 'Visible objects'), observation.entities.length);
    if (Array.isArray(observation.relations)) fact(t('可见关系数', 'Visible relationships'), observation.relations.length);
    if (Array.isArray(observation.availableActions)) fact(t('可执行行动数', 'Available actions'), objects(observation.availableActions).filter(c => c.available === true).length);
  } else if (event.eventType === 'AGENT_TOOL_CALL') {
    result.summary = t(`只读查询：${local(tools, str(p.tool))}。查询本身不修改图谱。`, `Read-only query: ${local(tools, str(p.tool))}. The query does not modify the graph.`);
    for (const key of ['query', 'entityId', 'from', 'to', 'depth']) if (p[key] !== undefined) fact(t(({ query: '查询内容', entityId: '查询对象', from: '起点', to: '终点', depth: '查询深度' } as Record<string, string>)[key]!, key), ['entityId', 'from', 'to'].includes(key) ? name(p[key]) : p[key]);
  } else if (event.eventType.startsWith('SIMULATION_EVENT_')) {
    const input = Object.keys(object(p.input)).length ? object(p.input) : p;
    const type = str(input.eventType);
    fact(t('输入类型', 'Input type'), ({ POTENTIAL_LEAK: t('潜在泄密信号', 'Potential-leak signal'), BANK_TRANSFER: t('模拟转账请求', 'Simulated transfer request'), USER_NOTE: t('用户备注', 'User note') } as Record<string, string>)[type] ?? type);
    if (input.targetId) fact(t('关联对象', 'Related object'), name(input.targetId));
    result.summary = t('输入事件为 Agent 提供后续决策信息，本身不直接改变对象状态。', 'The input informs later agent decisions; it does not directly change object state.');
  }
  if (['MASTER_TASK_STARTED','SUBTASK_DISPATCHED'].includes(event.eventType)) {
    result.summary = publicNarrative(object(p.request).goal, lang)||t('已安排授权范围内的任务，具体目标见原始事件。','An authorized task was arranged; see its original request for the goal.'); fact(t('读取版本','Input version'), p.inputVersion);
    fact(t('允许的行动','Allowed Actions'), object(p.request).allowedActions);
  } else if (event.eventType === 'MASTER_REVIEW') {
    const report = object(p.report);
    if (p.reportSource === 'ENGINE_FALLBACK') {
      result.title = t('本轮执行摘要', 'Round execution summary');
      result.summary = t('主 Agent 的结构化报告缺失或无效，系统已按执行记录汇总本轮。', 'The master report was missing or invalid. The system summarized the round from execution records.');
      result.tone = 'warning';
      fact(t('已结束子任务', 'Finished subtasks'), report.completedTasks);
      fact(t('已接受行动', 'Accepted Actions'), report.acceptedActions);
      fact(t('图谱查询', 'Graph queries'), report.queryCount);
    } else {
      result.summary = publicNarrative(report.summary, lang)||t('主 Agent 已完成本轮研判；请结合子任务的查询证据和执行回执查看，原始研判可展开阅读。','The master completed this review; inspect worker evidence and engine receipts, or expand the original report.');
      result.changes = [...(Array.isArray(report.findings) ? report.findings.map(v=>publicNarrative(v,lang)).filter(Boolean) : []), ...(Array.isArray(report.nextTasks) ? report.nextTasks.map(v=>publicNarrative(v,lang)).filter(Boolean).map(v => t('下一步：','Next: ') + v) : [])];
    }
  } else if (['SUBTASK_COMPLETED','MASTER_TASK_COMPLETED'].includes(event.eventType)) {
    result.summary = publicNarrative(object(p.result).summary,lang) || executionSummary([object(p.result)],lang);
    fact(t('任务状态','Task status'), ({SUCCEEDED:t('已完成','Completed'),FAILED:t('执行失败','Failed'),REJECTED:t('被拒绝','Rejected'),CANCELLED:t('已取消','Cancelled'),NO_ACTION:t('未执行操作','No Action submitted'),NO_EVIDENCE:t('未返回查询证据','No query evidence')} as Record<string,string>)[str(p.status)] ?? p.status);
  } else if (event.eventType === 'GRAPH_QUERY_RESULT') {
    const evidence=object(p.result); result.summary=t(`从当前授权图谱查询得到 ${str(evidence.totalObjects)} 个对象、${str(evidence.totalRelations)} 条关系；查询不修改状态。`,`Queried ${str(evidence.totalObjects)} objects and ${str(evidence.totalRelations)} relationships in the authorized runtime; no state changes.`);
    fact(t('状态版本','State version'),evidence.version); fact(t('结果已分页','Paged result'),evidence.truncated===true?t('是','Yes'):t('否','No'));
    fact(t('查询工具','Query tool'),p.tool);
  } else if(event.eventType==='SCENARIO_EVALUATED') {
    const outcome=object(p.outcome); result.tone=outcome.result==='SUCCEEDED'?'success':'warning';
    result.summary=outcome.result==='SUCCEEDED'?t('达到指定轮数，所有场景效果条件均满足。','At the configured horizon, all business conditions were met.'):outcome.result==='ERROR'?t('效果条件计算出错，请检查定义及数据依赖。','An outcome condition failed to evaluate; check its definition and data dependencies.'):t('达到指定轮数，仍有场景效果条件未满足。','At the configured horizon, some business conditions were not met.');
    result.changes=objects(outcome.conditions).map(c=>`${c.satisfied?'✓':'✗'} ${str(lang==='zh'?c.nameZh:c.nameEn)}`);
    if (outcome.result === 'NO_CONDITIONS') result.summary = local(terminationMessages, 'OBJECTIVE_NOT_VERIFIABLE');
  } else if (event.eventType === 'OBJECTIVE_REVIEWED') {
    result.summary = str(object(p.review).conclusion) || t('未能生成有可核验依据的结论。', 'A conclusion with verifiable references could not be generated.');
  } else if(event.eventType==='ACTION_TASK_RECEIPT') {
    result.summary=p.accepted===true?t('执行引擎已接受子任务的对象操作。','The engine accepted the worker’s object Action.'):t('执行引擎已拒绝该操作，图谱未改变。','The engine rejected the Action; the graph was unchanged.');
    fact(t('输入 / 输出版本','Input / output version'),`${str(p.inputVersion)} → ${str(p.outputVersion)}`);
  } else if(event.eventType==='MODEL_REQUESTED') {
    result.summary=t('主从调用共用模型请求和输出预算。','Master and workers share model-request and output budgets.'); fact(t('模型模式','Model mode'),modelModeLabel(p.modelMode,lang)); fact(t('请求序号','Request number'),p.requestNumber);
  }
  if(p.taskId) fact(t('任务标识','Task ID'),p.taskId);
  if(p.parentTaskId) fact(t('父任务','Parent task'),p.parentTaskId);
  if (p.evidence) fact(t('证据性质', 'Evidence'), ({ FACT: t('输入事实', 'Input fact'), INFERRED: t('推断', 'Inferred'), HYPOTHESIS: t('假设 / 行动提案', 'Hypothesis / proposal'), SIMULATED: t('模拟结果', 'Simulated result') } as Record<string, string>)[str(p.evidence)] ?? p.evidence);
  if (!result.summary) result.summary = t('该记录已保存；详细数据可在原始事件中查看。', 'This record was persisted; see the original event for its data.');
  return result;
}

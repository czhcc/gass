import type { ActionCatalog, Entity, Lang, Relation } from './types';

export interface NarrativeContext {
  entities?: Entity[];
  relations?: Relation[];
  catalog?: ActionCatalog;
}
type Labels = Record<string, [string, string]>;
export const actionLabels: Labels = {
  MARK_REVIEWED: ['标记审查', 'Mark reviewed'], ANALYZE_RELATIONS: ['分析关系', 'Analyze relations'], SIMULATE_PROPAGATION: ['模拟接收传播', 'Simulate exposure'],
  REVOKE_SHARING: ['撤销共享', 'Revoke sharing'], DISABLE_SYNC: ['禁用同步', 'Disable synchronization'], TRANSFER: ['模拟转账', 'Simulated transfer'],
  FLAG_RISK: ['标记模拟风险', 'Flag simulated risk'], ANALYZE_ACCOUNT: ['分析账户', 'Analyze account'], ASSESS_RISK_CEL: ['CEL 风险评分', 'CEL risk score'], END: ['结束 Agent 任务', 'End agent task'],
};
export const relationLabels: Labels = { SHARED_WITH: ['共享', 'Sharing'], SYNCS_TO: ['同步', 'Synchronization'], ACCESSED_BY: ['访问', 'Access'], STORED_IN: ['存储', 'Storage'], CONTACTS: ['联系', 'Contact'] };
const reportLabels: Labels = {
  NO_CONDITIONS: ['无可判定条件', 'no configured conditions'], SIMULATED: ['模拟结果', 'simulated result'],
  INFERRED: ['图谱推断', 'graph inference'], FACT: ['输入事实', 'input fact'], HYPOTHESIS: ['假设', 'hypothesis'],
  AUTHORIZED_RUNTIME: ['授权范围内的运行图谱', 'authorized runtime graph'],
  affectedRelationIds: ['被修改的关系', 'modified relationships'], exposureEvidence: ['模拟暴露的证据', 'exposure evidence'],
  simulationEvidence: ['模拟操作的证据', 'simulation evidence'], reviewEvidence: ['审查记录的证据', 'review evidence'],
  exposureSource: ['模拟传播来源', 'simulated exposure source'],
  graph_neighbors: ['查询邻居', 'query neighbors'], graph_path: ['查询路径', 'find a path'], graph_get_entity: ['查询对象', 'look up an object'],
  graph_subgraph: ['查询局部图谱', 'query the local graph'], graph_metrics: ['查询图谱指标', 'query graph metrics'],
};
const stateLabels: Record<string, [string, string, string, string]> = {
  exposed: ['已模拟暴露', '未模拟暴露', 'simulated exposure recorded', 'no simulated exposure recorded'],
  enabled: ['已启用', '已禁用', 'enabled', 'disabled'],
  reviewed: ['已审查', '未审查', 'reviewed', 'not reviewed'],
};

/** Replaces known codes for display using the graph at the current replay position. */
export function readableNarrative(value: unknown, lang: Lang, context: NarrativeContext = {}): string {
  if (typeof value !== 'string') return '';
  const index = lang === 'zh' ? 0 : 1;
  const names = new Map((context.entities ?? []).map(entity => [entity.id, entity.name]));
  const labels = new Map(Object.entries({ ...actionLabels, ...relationLabels, ...reportLabels }).map(([id, values]) => [id, values[index]]));
  for (const agent of context.catalog?.agents ?? []) {
    for (const { action } of agent.capabilities) labels.set(action.actionType, lang === 'zh' ? action.nameZh : action.nameEn);
  }
  for (const [id, name] of names) labels.set(id, name);
  for (const relation of context.relations ?? []) {
    const from = names.get(relation.sourceId), to = names.get(relation.targetId);
    if (!from || !to) continue;
    const type = relationLabels[relation.relationType]?.[index] ?? relation.relationType;
    labels.set(relation.id, lang === 'zh' ? `${from}到${to}的${type}关系` : `${type} from ${from} to ${to}`);
  }
  let text = value.replace(/^session_id:[^\r\n]*\r?\n\s*/, '').trim()
    .replace(/\*\*([^*\n]+)\*\*/g, '$1').replace(/`([^`\n]+)`/g, '$1')
    .replace(/(?<![\w.])"?(exposed|enabled|reviewed)"?\s*[=:]\s*(true|false)\b/g,
      (_, property: string, flag: string) => stateLabels[property][index * 2 + Number(flag === 'false')])
    .replace(/(?<![\w.])"?affectedRelationIds"?\s*[=:]\s*\[\s*\]/g,
      lang === 'zh' ? '未修改关系' : 'no relationships modified')
    .replace(/(?<![\w.])"?exposureSource"?\s*[=:]\s*"?([\w.-]*[\w])"?/g,
      (match, id: string) => names.has(id) ? (lang === 'zh' ? `模拟传播来源为${id}` : `simulated exposure from ${id}`) : match)
    .replace(/[A-Za-z0-9_]+(?:[.-][A-Za-z0-9_]+)*/g, token => lang === 'en' && /^(a|an|the|in|on|to|is|of|or)$/i.test(token) ? token : labels.get(token) ?? token)
    .replace(/[ \t]*(?:→|->)[ \t]*/g, lang === 'zh' ? ' 到 ' : ' to ');
  if (lang === 'zh') text = text.replace(/(\p{Script=Han})[ \t]+(?=\p{Script=Han})/gu, '$1');
  return text;
}

/** Keeps SDK transport metadata out of human-readable reports. Original records remain untouched. */
export function publicNarrative(value: unknown, lang: Lang, context?: NarrativeContext): string {
  if (typeof value !== 'string') return '';
  const text = readableNarrative(value, lang, context);
  if (lang === 'zh') {
    const chinese = (text.match(/\p{Script=Han}/gu) ?? []).length;
    const english = (text.match(/[A-Za-z]/g) ?? []).length;
    const words = (text.match(/[A-Za-z]{3,}/g) ?? []).length;
    if (!chinese || (words > 12 && english > chinese * 4)) return '';
  }
  return text;
}

/** Summarizes only persisted tool evidence, without inventing a translation or a model finding. */
export function executionSummary(results: Record<string, unknown>[], lang: Lang): string {
  const queries = results.reduce((n, r) => n + (Array.isArray(r.queryResults) ? r.queryResults.length : 0), 0);
  const receipts = results.flatMap(r => Array.isArray(r.receipts) ? r.receipts as {accepted?: boolean}[] : []);
  const accepted = receipts.filter(r => r.accepted === true).length;
  const rejected = receipts.filter(r => r.accepted === false).length;
  return lang === 'zh' ? `记录 ${queries} 次查询，引擎接受 ${accepted} 次操作、拒绝 ${rejected} 次操作。`
    : `${queries} queries recorded; ${accepted} Actions accepted and ${rejected} rejected by the engine.`;
}

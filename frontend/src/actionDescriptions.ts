import type { ActionDefinition, Lang } from "./types";

const effects: Record<string, string> = {
  SET_REVIEWED: '将当前对象标记为已审查（reviewed = true），审查结论记录在行动理由中。',
  READ_ONLY: '记录对当前对象的只读分析，不修改对象状态或关系；关系可达不代表已经发生泄密。',
  EXPOSE_ENTITY: '将当前对象标记为模拟暴露（exposed = true），仅表示模拟结果，不代表真实泄密。',
  REVOKE_SHARING_WITH: '由执行引擎关闭当前对象指向指定接收对象的所有已启用共享关系；其他对象及关系保持不变。',
  DISABLE_SYNC_TO: '由执行引擎关闭当前系统指向指定目标对象的所有已启用同步关系；其他对象及关系保持不变。',
};
const conditions: Record<string, string> = {
  NOT_REVIEWED: '当前对象尚未标记为已审查。',
  NOT_EXPOSED: '当前对象尚未标记为模拟暴露。',
  ENABLED_DIRECT_SOURCE: '传播来源必须对执行 Agent 可见，且为文档或已模拟暴露的对象，并通过已启用的直接关系指向当前对象。',
  ENABLED_SHARING_RECIPIENT: '当前对象与指定接收对象之间，必须存在已启用的对外共享关系，且接收对象对执行 Agent 可见。',
  ENABLED_SYNC_DESTINATION: '当前系统与指定目标对象之间，必须存在已启用的对外同步关系，且目标对象对执行 Agent 可见。',
};
const parameters: Record<string, [string, string]> = {
  recipientId: ['共享接收对象', '指定要撤销共享的接收对象 ID，由当前对象撤销向该对象的共享。'],
  destinationId: ['同步目标对象', '指定要停止同步的目标对象 ID，由当前系统停止向该对象同步。'],
  sourceId: ['传播来源对象', '指定模拟传播的来源对象 ID，当前对象作为接收方。'],
};
const reasons: Record<string, string> = {
  'Object is already reviewed': '当前对象已标记为已审查。',
  'Object is already simulated exposed': '当前对象已标记为模拟暴露。',
  'No visible eligible source with an enabled direct propagation path': '没有对该 Agent 可见且通过已启用直接关系指向当前对象的有效传播来源。',
  'No visible recipient with enabled outgoing sharing': '没有对该 Agent 可见且与当前对象存在已启用共享关系的接收对象。',
  'No visible destination with enabled outgoing synchronization': '没有对该 Agent 可见且与当前系统存在已启用同步关系的目标对象。',
};
export const effectDescription = (action: ActionDefinition, lang: Lang) => lang === 'zh'
  ? effects[action.effect] ?? action.description : action.description;
export const conditionDescription = (code: string, lang: Lang) => lang === 'zh'
  ? conditions[code] ?? `暂无中文条件说明（${code}）。` : code;
export const parameterLabel = (name: string, lang: Lang) => lang === 'zh' ? parameters[name]?.[0] ?? name : name;
export const unavailableDescription = (reason: string, lang: Lang) => lang === 'zh' ? reasons[reason] ?? reason : reason;
export function parameterDetails(action: ActionDefinition, lang: Lang) {
  const schema = action.parametersSchema;
  const fields = (schema.properties ?? {}) as Record<string, { type?: string; description?: string }>;
  const required = Array.isArray(schema.required) ? schema.required : [];
  return Object.entries(fields).map(([name, field]) => ({
    name, label: parameterLabel(name, lang), required: required.includes(name),
    type: lang === 'zh' ? parameters[name] ? '对象 ID（文本）' : ({ string: '文本', number: '数字', integer: '整数', boolean: '布尔值', object: '对象', array: '列表' } as Record<string, string>)[field.type ?? ''] ?? field.type ?? '未指定' : field.type ?? 'Unspecified',
    description: lang === 'zh' ? (action.program ? field.description ?? parameters[name]?.[1] : parameters[name]?.[1] ?? field.description) ?? '暂无参数说明。' : field.description ?? '',
  }));
}

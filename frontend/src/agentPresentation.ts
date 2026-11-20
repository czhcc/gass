import type { Lang } from './types';

const roles = [
  { id: 'investigation', zh: '调查 Agent', en: 'Investigation Agent', aliases: ['Investigator', '调查 / Investigation Agent', '调查 Agent'] },
  { id: 'propagation', zh: '传播 Agent', en: 'Propagation Agent', aliases: ['传播 / Propagation Agent', '传播 Agent'] },
  { id: 'response', zh: '响应 Agent', en: 'Response Agent', aliases: ['响应 / Response Agent', '响应 Agent'] },
  { id: 'evaluation', zh: '评估 Agent', en: 'Evaluation Agent', aliases: ['评估 / Evaluation Agent', '评估 Agent'] },
  { id: 'bank', zh: '银行 Agent', en: 'Bank Agent', aliases: ['银行 / Bank Agent', '银行 Agent'] },
  { id: 'risk', zh: '风控 Agent', en: 'Risk Agent', aliases: ['风控 / Risk Agent', '风控 Agent'] },
];
const goals: Record<string, string> = {
  'Investigate potential paths and record simulated reviews. Connectivity is not proof of propagation.': '调查潜在传播路径并记录模拟审查；图谱连通不代表已发生传播。',
  'Reduce simulated propagation risk using currently enabled channels. Review the updated state each round.': '根据当前启用的通道降低模拟传播风险，每轮重新检查最新状态。',
  'Process simulated transfer': '处理模拟转账请求。',
  'Flag simulated transfer risk': '识别并标记模拟转账风险。',
  'Process a simulated transfer with exact money conservation': '处理模拟转账，使用精确金额结算并保持资金守恒。',
  'Review current transfers and flag simulated risks': '审查当前转账并标记模拟风险。',
  'Investigate visible leak paths and review objects': '调查可见的潜在泄密路径并审查相关对象。',
  'Simulate hypothetical propagation only over enabled current paths': '仅沿当前启用的路径模拟假设传播，结果不代表真实泄密。',
  'Reduce risk using observed channel state': '根据观察到的通道状态采取行动，降低模拟风险。',
  'Evaluate current visible relationships': '评估当前可见的对象关系。',
};

/** Localizes recognized built-in labels without replacing user-defined names. */
export function agentDisplayName(agent: { id: string; name?: string }, lang: Lang): string {
  const role = roles.find(r => agent.name ? r.en === agent.name || r.aliases.includes(agent.name) : r.id === agent.id);
  return role ? role[lang] : agent.name || agent.id;
}

/** Existing frozen scenarios keep their original goals; only the presentation is localized. */
export function agentGoalDescription(goal: string | undefined, lang: Lang): string {
  return lang === 'zh' ? goals[goal?.trim() ?? ''] ?? goal ?? '' : goal ?? '';
}

export function modelModeLabel(mode: unknown, lang: Lang): string {
  if (mode === 'LLM') return lang === 'zh' ? '真实模型（LLM）' : 'Configured model (LLM)';
  if (mode === 'MOCK') return lang === 'zh' ? '模拟模型（MOCK）' : 'Mock model (MOCK)';
  return typeof mode === 'string' ? mode : '';
}

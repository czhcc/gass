import { expect, it } from 'vitest';
import { agentDisplayName, agentGoalDescription, modelModeLabel } from './agentPresentation';

it('localizes the names and goals used by existing two-agent scenarios', () => {
  const investigator = { id: 'investigation', name: 'Investigation Agent', goal: 'Investigate potential paths and record simulated reviews. Connectivity is not proof of propagation.' };
  expect(agentDisplayName(investigator, 'zh')).toBe('调查 Agent');
  expect(agentGoalDescription(investigator.goal, 'zh')).toBe('调查潜在传播路径并记录模拟审查；图谱连通不代表已发生传播。');
  const goal = 'Reduce simulated propagation risk using currently enabled channels. Review the updated state each round.';
  expect(agentGoalDescription(goal, 'zh')).toBe('根据当前启用的通道降低模拟传播风险，每轮重新检查最新状态。');
  expect(agentDisplayName(investigator, 'en')).toBe('Investigation Agent');
  expect(agentGoalDescription(investigator.goal, 'en')).toBe(investigator.goal);
});

it.each([
  ['bank', '银行 / Bank Agent', '银行 Agent', 'Process a simulated transfer with exact money conservation'],
  ['risk', '风控 / Risk Agent', '风控 Agent', 'Review current transfers and flag simulated risks'],
  ['investigation', '调查 / Investigation Agent', '调查 Agent', 'Investigate visible leak paths and review objects'],
  ['propagation', '传播 / Propagation Agent', '传播 Agent', 'Simulate hypothetical propagation only over enabled current paths'],
  ['response', '响应 / Response Agent', '响应 Agent', 'Reduce risk using observed channel state'],
  ['evaluation', '评估 / Evaluation Agent', '评估 Agent', 'Evaluate current visible relationships'],
])('localizes the %s demo role without changing persisted definitions', (id, name, zh, goal) => {
  expect(agentDisplayName({ id, name }, 'zh')).toBe(zh);
  expect(agentGoalDescription(goal, 'zh')).not.toBe(goal);
});

it('keeps custom names and goals intact instead of guessing their meaning from an agent ID', () => {
  const custom = { id: 'investigation', name: 'Custom Investigator', goal: 'Check only documents tagged urgent' };
  expect(agentDisplayName(custom, 'zh')).toBe(custom.name);
  expect(agentGoalDescription(custom.goal, 'zh')).toBe(custom.goal);
  expect(modelModeLabel('LLM', 'zh')).toBe('真实模型（LLM）');
  expect(modelModeLabel('MOCK', 'zh')).toBe('模拟模型（MOCK）');
});

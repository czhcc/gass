import { afterEach, expect, it } from 'vitest';
import { cleanup, render, screen, fireEvent } from '@testing-library/vue';
import EventDetails from './components/EventDetails.vue';
import { describeEvent } from './eventPresentation';
import type { ActionDefinition, RunEvent, RunState } from './types';
afterEach(cleanup);
const entity = (id: string, name: string, properties = {}) => ({ id, name, type: 'PERSON', properties });
const entities = [entity('a', '人员 A'), entity('c', '人员 C')];
const agents = [{ id: 'response', name: 'Response Agent', goal: '', modelId: 'm', allowedActions: [] }];
const event = (sequence: number, eventType: string, payload: Record<string, unknown>, agentId: string | null = 'response'): RunEvent => ({ runId: 'r', sequence, eventType, payload, agentId, simulationTime: 2 });
const proposed = event(1, 'ACTION_PROPOSED', { action: { actionType: 'REVOKE_SHARING', targetId: 'a', parameters: { recipientId: 'c' }, reason: 'Original model explanation' } });
const accepted = event(2, 'ACTION_ACCEPTED', { actionType: 'REVOKE_SHARING', targetId: 'a', affectedRelationIds: ['share'], reason: 'Declarative CEL action committed as SIMULATED' });
const context = { events: [proposed, accepted], entities, agents, relations: [] };

it('labels rule work and displays its frozen revision and cause', () => {
  const queued = event(4, 'RULE_ACTION_SCHEDULED', { ruleId: 'r1', ruleRevision: 2, dueTime: 9,
    causeEventRef: { runId: 'parent', sequence: 3 }, actionOrigin: 'SYSTEM_RULE' }, null);
  const result = describeEvent(queued, context, 'en');
  expect(result.title).toBe('Rule action scheduled');
  expect(result.facts).toContainEqual({ label: 'Rule revision', value: 'r1 · 2' });
  expect(result.facts).toContainEqual({ label: 'Cause event', value: 'parent #3' });
});

it('shows readable objective conclusions while preserving the original event', () => {
  const finalEvent = event(3, 'OBJECTIVE_REVIEWED', { review: { conclusion: 'a 执行了 REVOKE_SHARING，引擎返回 NO_CONDITIONS。' } });
  render(EventDetails, { props: { event: finalEvent, context, lang: 'zh' } });
  expect(screen.getByText('人员 A 执行了撤销共享，引擎返回无可判定条件。')).toBeTruthy();
  expect(screen.getByText(/a 执行了 REVOKE_SHARING/).closest('details')?.open).toBe(false);
});

it('labels a fallback round review as an execution summary and shows its counts', () => {
  const fallback = event(3, 'MASTER_REVIEW', { reportSource: 'ENGINE_FALLBACK', report: { completedTasks: 2, acceptedActions: 1, queryCount: 3 } }, 'lead');
  const description = describeEvent(fallback, context, 'zh');
  expect(description.title).toBe('本轮执行摘要');
  expect(description.summary).toContain('结构化报告');
  expect(description.facts).toContainEqual({ label: '已接受行动', value: '1' });
});

it('shows the specific runtime diagnosis for a recorded model timeout', () => {
  const failure = event(3, 'RUN_FAILED', { reasonCode: 'MODEL_DECISION_FAILED',
    diagnosticCode: 'MODEL_REQUEST_TIMEOUT', exceptionType: 'TimeoutException' }, null);
  render(EventDetails, { props: { event: failure, context, lang: 'zh' } });
  expect(screen.getByText('模型请求超时')).toBeTruthy();
  expect(screen.getByText('TimeoutException')).toBeTruthy();
});

it('shows logical clock jumps separately from committed graph versions', () => {
  const jump = { ...event(3, 'SIMULATION_CLOCK_ADVANCED', { previousTime: 2, stateVersion: 4 }, null), simulationTime: 100 };
  const description = describeEvent(jump, context, 'en');
  expect(description.title).toBe('Simulation clock advanced');
  expect(description.facts).toContainEqual({ label: 'Simulation time', value: '2 → 100' });
  expect(description.facts).toContainEqual({ label: 'Graph version', value: '4' });
  const horizon = event(4, 'RUN_COMPLETED', { reasonCode: 'HORIZON_REACHED', pendingWork: 3 }, null);
  expect(describeEvent(horizon, context, 'zh').summary).toContain('时限');
  expect(describeEvent(horizon, context, 'en').facts).toContainEqual({ label: 'Remaining work', value: '3' });
});

it('explains the actor, owner, related object and verdict while preserving raw data in a closed disclosure', async () => {
  render(EventDetails, { props: { event: accepted, context, lang: 'zh' } });
  expect(screen.getByRole('heading', { name: '行动已接受' })).toBeTruthy();
  expect(screen.getByText(/人员 A.*人员 C/)).toBeTruthy();
  expect(screen.getByText('响应 Agent')).toBeTruthy();
  const disclosure = screen.getByText('技术详情（原始事件 JSON）').closest('details')!;
  expect(disclosure.open).toBe(false);
  expect(disclosure.textContent).toContain('ACTION_ACCEPTED');
  await fireEvent.click(screen.getByText('技术详情（原始事件 JSON）'));
  expect(screen.getByText(/"affectedRelationIds"/)).toBeTruthy();
});

it('distinguishes a proposal, a rejected action and a read-only analysis without claiming a committed change', () => {
  expect(describeEvent(proposed, context, 'zh').summary).toContain('待引擎校验');
  const rejected = event(2, 'ACTION_REJECTED', { actionType: 'REVOKE_SHARING', targetId: 'a', code: 'ACTION_TARGET_HIDDEN', reason: 'Object is outside scope' });
  const text = describeEvent(rejected, context, 'zh');
  expect(text.summary).toContain('未执行');
  expect(text.facts.some(f => f.value.includes('可见范围'))).toBe(true);
  const analysis = describeEvent(event(3, 'ACTION_ACCEPTED', { actionType: 'ANALYZE_RELATIONS', targetId: 'a' }), context, 'zh');
  expect(analysis.summary).toContain('只读分析');
  expect(analysis.summary).toContain('不修改');
});

it('renders changes from the event snapshots, not from the current live state', () => {
  const before: RunState = { runId: 'r', simulationTime: 2, version: 0, entities: [entity('a', '人员 A', { reviewed: false })], relations: [] };
  const after: RunState = { ...before, version: 1, entities: [entity('a', '人员 A', { reviewed: true })] };
  const changed = event(3, 'WORLD_STATE_CHANGED', { before, after, version: 1, evidence: 'SIMULATED' });
  const result = describeEvent(changed, { ...context, entities: [entity('a', '人员 A', { reviewed: false })] }, 'zh');
  expect(result.changes).toContain('人员 A · 审查状态：未审查 → 已审查');
  expect(result.facts.some(f => f.value === '0 → 1')).toBe(true);
});

it('describes disabled relationships, bank balances and newly created transactions', () => {
  const relation = { id: 'share', sourceId: 'a', targetId: 'c', relationType: 'SHARED_WITH', enabled: true, evidence: 'FACT' };
  const before: RunState = { runId: 'r', simulationTime: 2, version: 0, entities: [entity('a', '账户 A', { balanceMinor: 10000, currency: 'CNY' })], relations: [relation] };
  const after: RunState = { ...before, version: 1, entities: [entity('a', '账户 A', { balanceMinor: 9750, currency: 'CNY' }), { ...entity('tx', '模拟交易'), type: 'TRANSACTION' }], relations: [{ ...relation, enabled: false }] };
  const changes = describeEvent(event(4, 'WORLD_STATE_CHANGED', { before, after }), context, 'zh').changes.join(' ');
  expect(changes).toContain('100.00 CNY → 97.50 CNY');
  expect(changes).toContain('已启用 → 已禁用');
  expect(changes).toContain('新增对象：模拟交易');
});

it('explains budget exhaustion and preserves unknown event kinds without inventing success', () => {
  const result = describeEvent(event(86, 'RUN_BUDGET_EXHAUSTED', { reasonCode: 'MAX_STEPS', actual: 6, limit: 6 }, null), context, 'zh');
  expect(result.summary).toContain('轮数');
  expect(result.facts).toContainEqual({ label: '触发值 / 限额', value: '6 / 6' });
  expect(describeEvent(event(99, 'FUTURE_EVENT', { newData: 'x' }), context, 'zh').title).toContain('FUTURE_EVENT');
  expect(describeEvent(accepted, context, 'en').title).toBe('Action accepted');
});

it('does not recover parameters or explanations from a later or unrelated proposal', () => {
  const wrong = event(1, 'ACTION_PROPOSED', { action: { actionType: 'REVOKE_SHARING', targetId: 'c', parameters: { recipientId: 'a' } } });
  const future = { ...proposed, sequence: 5 };
  const result = describeEvent(accepted, { ...context, events: [wrong, accepted, future] }, 'zh');
  expect(result.facts.some(f => f.label === '共享接收对象')).toBe(false);
  const interrupted = event(3, 'ACTION_ACCEPTED', accepted.payload);
  expect(describeEvent(interrupted, context, 'zh').facts.some(f => f.label === '共享接收对象')).toBe(false);
});

it('uses custom names and conditions and never assumes a revised analysis Action is read-only', () => {
  const definition: ActionDefinition = { actionType: 'ANALYZE_RELATIONS', nameZh: '分析并记录评分', nameEn: 'Analyze and score', description: '计算属性', effect: 'CUSTOM', preconditions: [], parametersSchema: { properties: {} },
    program: { version: 1, conditions: [{ expression: 'false', messageZh: '当前对象不符合评分条件', messageEn: 'Scoring condition not met' }], operations: [{ op: 'SET_PROPERTY', arguments: { object: 'target.id', property: "'score'", value: '20' } }], parameterOptions: {} } };
  const observed = event(1, 'AGENT_OBSERVATION', { observation: { availableActions: [{ action: definition }] } });
  const revisedContext = { ...context, events: [observed] };
  const result = describeEvent(event(4, 'ACTION_ACCEPTED', { actionType: definition.actionType, targetId: 'a' }), revisedContext, 'zh');
  expect(result.facts).toContainEqual({ label: '行动', value: '分析并记录评分' });
  expect(result.summary).not.toContain('不修改');
  const denied = describeEvent(event(5, 'ACTION_REJECTED', { actionType: definition.actionType, targetId: 'a', code: 'ACTION_PRECONDITION_FAILED', reason: 'Scoring condition not met' }), revisedContext, 'zh');
  expect(denied.facts).toContainEqual({ label: '拒绝原因', value: '当前对象不符合评分条件' });
});

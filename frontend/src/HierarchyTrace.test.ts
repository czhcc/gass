import {afterEach,expect,it} from 'vitest';
import {cleanup,render,screen} from '@testing-library/vue';
import HierarchyTrace from './components/HierarchyTrace.vue';
import type {Agent,RunEvent,TaskRecord} from './types';
afterEach(cleanup);
const agents:Agent[]=[{id:'lead',name:'主 Agent',goal:'规划',modelId:'m',allowedActions:[]},{id:'bank',name:'转账 Agent',goal:'转账',modelId:'m',allowedActions:['TRANSFER']}];
const tasks:TaskRecord[]=[{runId:'r',taskId:'root',parentTaskId:null,agentId:'lead',round:1,status:'SUCCEEDED',inputVersion:0,outputVersion:1,request:{goal:'整体研判',scopeIds:[],allowedActions:[],inputEventId:null},result:{report:{summary:'已核对转账回执',findings:['资金守恒'],nextTasks:['审查风险']}},createdSequence:2,completedSequence:9},{runId:'r',taskId:'child',parentTaskId:'root',agentId:'bank',round:1,status:'SUCCEEDED',inputVersion:0,outputVersion:1,request:{goal:'执行转账',scopeIds:['a','b'],allowedActions:['TRANSFER'],inputEventId:'e'},result:{receipts:[{accepted:true,actionType:'TRANSFER',targetId:'b',inputVersion:0,outputVersion:1}],summary:'已完成转账'},createdSequence:4,completedSequence:8}];
const events:RunEvent[]=[{runId:'r',sequence:10,eventType:'SCENARIO_EVALUATED',simulationTime:1,agentId:null,payload:{outcome:{result:'FAILED',evaluatedRound:1,version:1,conditions:[{id:'risk',nameZh:'账户已审查',nameEn:'Accounts reviewed',satisfied:false,error:''}]}}}];
it('renders task hierarchy and distinguishes a finished run from failed business effects',()=>{
  render(HierarchyTrace,{props:{tasks,events,agents,cursor:10,lang:'zh'}});
  expect(screen.getByRole('region',{name:'主从任务与研判'})).toBeTruthy();
  expect(screen.getByText('已核对转账回执')).toBeTruthy(); expect(screen.getByText('资金守恒')).toBeTruthy();
  expect(screen.getAllByText(/执行转账/).some(node=>node.tagName==='SUMMARY')).toBe(true); expect(screen.getByText(/未达成/)).toBeTruthy(); expect(screen.getByText(/账户已审查/)).toBeTruthy();
});
it('hides future task results and outcome while replaying an earlier event',()=>{
  render(HierarchyTrace,{props:{tasks,events,agents,cursor:4,lang:'en'}});
  expect(screen.queryByText('已核对转账回执')).toBeNull(); expect(screen.queryByText('已完成转账')).toBeNull();
  expect(screen.queryByText('Accounts reviewed')).toBeNull(); expect(screen.getAllByText(/Running/).length).toBe(2);
});
it('uses Chinese execution-record summaries for older English reports and keeps original text collapsed',()=>{
  const older=structuredClone(tasks);
  older[0]!.result={report:{summary:'Round 1 established the structure but achieved no state changes.',findings:['The response worker returned a receipt.'],nextTasks:['Review the next account.']}};
  older[1]!.request.goal='Query objects and relations for account_b';
  older[1]!.result.summary='session_id: example-session\n\n**Query Result Summary**: tool output received.';
  const queryAgent={...agents[1]!,runtime:{role:'QUERY' as const,toolName:'delegate_query',description:'Query',tools:['graph_get_entity']}};
  const {container}=render(HierarchyTrace,{props:{tasks:older,events:[],agents:[agents[0]!,queryAgent],cursor:9,lang:'zh'}});
  expect(screen.getByText(/本轮委派 1 项子任务/)).toBeTruthy();
  expect(screen.getByText(/查询关联对象并分析图谱/)).toBeTruthy();
  expect(screen.getByText(/Round 1 established the structure but achieved no state changes./).closest('details')?.open).toBe(false);
  expect(Array.from(container.querySelectorAll('p')).filter(p=>!p.closest('details details')&&!p.closest('[data-original-report]')).some(p=>p.textContent?.includes('session_id:'))).toBe(false);
});

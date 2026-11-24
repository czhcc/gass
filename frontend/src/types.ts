export type Lang = "zh" | "en";
export interface Entity {
  id: string;
  type: string;
  name: string;
  properties: Record<string, unknown>;
}
export interface Relation {
  id: string;
  sourceId: string;
  targetId: string;
  relationType: string;
  enabled: boolean;
  evidence: string;
}
export interface World {
  id: string;
  name: string;
  domainCode: string;
  version: number;
  entityTypes: string[];
  relationTypes: string[];
  entities: Entity[];
  relations: Relation[];
  ontology?: Ontology | null;
}

export interface ActionDefinition {
  actionType: string;
  nameZh: string;
  nameEn: string;
  description: string;
  parametersSchema: Record<string, unknown>;
  preconditions: string[];
  effect: string;
  program?: ActionProgram | null;
}
export interface ActionProgram {
  version: number;
  conditions: { expression: string; messageZh: string; messageEn: string }[];
  operations: { op: string; arguments: Record<string, string> }[];
  parameterOptions: Record<string, string>;
}
export interface ActionLibraryEntry {
  id: string; domainCode: string; ownerType: string; revision: number;
  status: 'DRAFT' | 'PUBLISHED'; source: string; modelId: string | null;
  definition: ActionDefinition; version: number;
}
export interface Ontology {
  version: number;
  types: { targetKind: string; type: string; actions: ActionDefinition[] }[];
}
export interface ActionCapability {
  targetKind: string;
  targetId: string;
  targetName: string;
  type: string;
  action: ActionDefinition;
  available: boolean;
  unavailableReason: string;
  sourceIds: string[];
  parameterOptions?: Record<string, string[]>;
}
export interface ActionCatalog {
  domainCode: string;
  version: number;
  agents: { agentId: string; capabilities: ActionCapability[]; ended?: boolean }[];
}
export interface ObjectActionInput {
  agentId: string;
  expectedVersion: number;
  decision: { actionType: string; targetId: string; targetKind: string; parameters: Record<string, unknown>; reason: string };
}
export interface Model {
  id: string;
  name: string;
  modelName: string;
  baseUrl: string;
  keyConfigured: boolean;
  apiKeyEnv: string | null;
  temperature: number;
  maxTokens: number;
  timeoutSeconds: number;
  concurrency: number;
}
export interface Scenario {
  id: string;
  name: string;
  worldId: string;
  objective?: string | null;
}

export interface Run {
  id: string;
  scenarioId: string;
  status: string;
  step: number;
  maxSteps: number;
  version: number;
  activeRuntimeMs?: number;
  terminationReason?: string | null;
  objective?: string | null;
}
export interface RunState {
  runId: string;
  simulationTime: number;
  version: number;
  entities: Entity[];
  relations: Relation[];
}
export interface RunEvent {
  runId: string;
  sequence: number;
  eventType: string;
  simulationTime: number;
  agentId: string | null;
  payload: Record<string, unknown>;
}
export interface Agent {
  id: string;
  name: string;
  goal: string;
  modelId: string;
  allowedActions: string[];
  visibleEntityIds?: string[];
  maxDecisions?: number;
  runtime?: {role: 'MASTER' | 'QUERY' | 'ACTION'; toolName: string; description: string; tools: string[]};
}
export interface TeamConfig {
  rootAgentId:string; maxDelegationsPerRound:number; maxActionsPerRound:number;
  mockRules:{actionType:string;eventType:string|null;whenExpression:string}[];
  conditions:GoalCondition[];
  inputActions:Record<string,string[]>;
}
export interface GoalCondition {
  id: string;
  nameZh: string;
  nameEn: string;
  expression: string;
  evidenceExpression?: string | null;
}
export interface ObjectiveDraft {
  conditions: GoalCondition[];
  unresolved: string[];
  mode: 'MOCK' | 'LLM';
}
export interface ObjectiveReview {
  conclusion: string;
  evidenceSequences: number[];
  limitations: string;
}
export interface ScenarioProfile {
  domainCode:string;nameZh:string;nameEn:string;
  agents:Omit<Agent,'modelId'>[];team:TeamConfig;
}
export interface TaskRecord {
  runId:string;taskId:string;parentTaskId:string|null;agentId:string;round:number;status:string;
  inputVersion:number;outputVersion:number;
  request:{goal:string;scopeIds:string[];allowedActions:string[];inputEventId:string|null};
  result:Record<string,unknown>;createdSequence:number;completedSequence:number|null;
}
export interface BusinessOutcome {
  result:string;evaluatedRound:number;version:number;
  conditions:{id:string;nameZh:string;nameEn:string;satisfied:boolean;error:string;evidence?:unknown;evidenceError?:string|null}[];
}
export interface Difference {
  id: string;
  before: unknown;
  after: unknown;
}
export interface CompareResult {
  leftId: string;
  rightId: string;
  entityDifferences: Difference[];
  relationDifferences: Difference[];
  leftMetrics: Record<string, unknown>;
  rightMetrics: Record<string, unknown>;
  summary: string;
}

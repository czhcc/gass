<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { storeToRefs } from 'pinia';
import { api } from '../api';
import { useWorkspaceStore } from '../stores/workspace';
import type { ActionDefinition, ActionLibraryEntry, ActionProgram, Lang, RunState, World } from '../types';
const props = defineProps<{ lang: Lang }>();
const text = (zh: string, en: string) => props.lang === 'zh' ? zh : en;
const { worlds, models } = storeToRefs(useWorkspaceStore());
const entries = ref<ActionLibraryEntry[]>([]), selected = ref<ActionLibraryEntry>();
const busy = ref(false), error = ref(''), message = ref(''), search = ref('');
const domain = ref('information'), ownerType = ref('DOCUMENT');
const actionType = ref(''), nameZh = ref(''), nameEn = ref(''), description = ref(''), effect = ref('CUSTOM');
type Parameter = { name: string; type: string; description: string; required: boolean; reference: boolean; options: string; enumJson: string };
const parameters = ref<Parameter[]>([]), conditions = ref<ActionProgram['conditions']>([]), operations = ref<ActionProgram['operations']>([]);
const legacyConditions = ref<string[]>([]);
const ownerTypes = computed(() => [...new Set([
  ...(domain.value === 'bank' ? ['BANK_ACCOUNT', 'TRANSACTION'] : ['DOCUMENT', 'PERSON', 'SYSTEM', 'ORGANIZATION']),
  ...worlds.value.filter(w => w.domainCode === domain.value).flatMap(w => w.entityTypes),
])]);
const published = computed(() => selected.value?.status === 'PUBLISHED');
const filtered = computed(() => entries.value.filter(e => e.domainCode === domain.value &&
  (!search.value || (e.definition.actionType + e.definition.nameZh + e.definition.nameEn + e.ownerType).toLowerCase().includes(search.value.toLowerCase()))));
const modelId = ref(''), request = ref('');
const selectedModel = computed(() => models.value.find(m => m.id === modelId.value));
const worldId = ref(''), targetId = ref(''), previewParameters = ref('{}');
const matchingWorlds = computed(() => worlds.value.filter(w => w.domainCode === domain.value && w.entityTypes.includes(ownerType.value)));
const world = computed(() => matchingWorlds.value.find(w => w.id === worldId.value));
const targets = computed(() => world.value?.entities.filter(e => e.type === ownerType.value) ?? []);
const preview = ref<{ accepted: boolean; message: string; state: RunState }>();
const boundActions = computed(() => world.value?.ontology?.types.find(t => t.type === ownerType.value)?.actions ?? []);
const confirmation = ref<{ kind: 'draft' | 'binding'; id: string; version: number; ownerType?: string; actionType?: string }>();
const cancelButton = ref<HTMLButtonElement>();
let dialogTrigger: HTMLElement | null = null;
const args = { SET_PROPERTY: ['object', 'property', 'value'], DISABLE_RELATIONS: ['source', 'target', 'relationType'], TRANSFER_MONEY: ['source', 'target', 'amount', 'currency'] } as Record<string, string[]>;
const argName = (key: string) => text(({ object: '对象 ID（CEL）', property: '属性名（CEL）', value: '属性值（CEL）', source: '来源对象（CEL）', target: '关联对象（CEL）', relationType: '关系类型（CEL）', amount: '金额文本（CEL）', currency: '币种（CEL）' } as Record<string, string>)[key] ?? key, key + ' (CEL)');
watch(domain, () => { ownerType.value = ownerTypes.value[0] ?? ''; selected.value = undefined; worldId.value = ''; });
watch(ownerType, () => { if (!matchingWorlds.value.some(w => w.id === worldId.value)) worldId.value = ''; });
watch(worldId, () => { targetId.value = targets.value[0]?.id ?? ''; preview.value = undefined; });
function definition(): ActionDefinition {
  const fields: Record<string, unknown> = {}, options: Record<string, string> = {};
  for (const p of parameters.value) {
    if (!p.name || p.name in fields) throw new Error(text('参数名不能为空或重复。', 'Parameter names must be unique and non-empty.'));
    fields[p.name] = { type: p.type, description: p.description, ...(p.reference ? { format: 'entity-id' } : {}), ...(p.enumJson.trim() ? { enum: JSON.parse(p.enumJson) } : {}) };
    if (p.options.trim()) options[p.name] = p.options;
  }
  return { actionType: actionType.value, nameZh: nameZh.value, nameEn: nameEn.value, description: description.value, effect: effect.value, preconditions: legacyConditions.value,
    parametersSchema: { type: 'object', properties: fields, required: parameters.value.filter(p => p.required).map(p => p.name).sort(), additionalProperties: false },
    program: { version: 1, conditions: conditions.value, operations: operations.value, parameterOptions: options } };
}
function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value && typeof value === 'object') return '{' + Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => JSON.stringify(k) + ':' + canonical(v)).join(',') + '}';
  return JSON.stringify(value);
}
const dirty = computed(() => { try { return !!selected.value && canonical(definition()) !== canonical(selected.value.definition); } catch { return true; } });
function load(e: ActionLibraryEntry) {
  // Set the domain first so its watcher does not clear the selected revision.
  domain.value = e.domainCode;
  void nextTick(() => {
    selected.value = e; ownerType.value = e.ownerType;
    const a = JSON.parse(JSON.stringify(e.definition)) as ActionDefinition;
    actionType.value = a.actionType; nameZh.value = a.nameZh; nameEn.value = a.nameEn; description.value = a.description; effect.value = a.effect;
    legacyConditions.value = a.preconditions ?? [];
    const fields = (a.parametersSchema.properties ?? {}) as Record<string, { type: string; description?: string; format?: string; enum?: unknown }>;
    const required = (a.parametersSchema.required ?? []) as string[];
    parameters.value = Object.entries(fields).map(([name, p]) => ({ name, type: p.type, description: p.description ?? '', required: required.includes(name), reference: p.format === 'entity-id', options: a.program?.parameterOptions[name] ?? '', enumJson: p.enum ? JSON.stringify(p.enum) : '' }));
    conditions.value = a.program?.conditions ?? []; operations.value = a.program?.operations ?? [];
    preview.value = undefined;
  });
}
function newDraft(copy = false) {
  selected.value = undefined; message.value = ''; error.value = ''; preview.value = undefined;
  if (!copy) { actionType.value = ''; nameZh.value = ''; nameEn.value = ''; description.value = ''; effect.value = 'CUSTOM'; parameters.value = []; conditions.value = []; operations.value = []; legacyConditions.value = []; }
}
function operationDefaults(index: number) {
  const op = operations.value[index]!;
  op.arguments = Object.fromEntries((args[op.op] ?? []).map(k => [k, k === 'object' || k === 'source' ? 'target.id' : k === 'property' ? "'customScore'" : k === 'value' ? '0' : "''"]));
}
async function perform(task: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true; error.value = ''; message.value = '';
  try { await task(); } catch (e) { error.value = String(e); } finally { busy.value = false; }
}
async function refresh() { entries.value = await api<ActionLibraryEntry[]>('/action-library'); }
onMounted(() => perform(refresh));
const input = () => ({ domainCode: domain.value, ownerType: ownerType.value, definition: definition(), version: selected.value?.version ?? 0 });
function validate() { void perform(async () => { await api('/action-library/validate', 'POST', input()); message.value = text('定义校验通过', 'Definition is valid'); }); }
function save() { void perform(async () => {
  const saved = await api<ActionLibraryEntry>(selected.value ? '/action-library/' + selected.value.id : '/action-library', selected.value ? 'PUT' : 'POST', input());
  await refresh(); load(saved); message.value = text('草稿已保存', 'Draft saved');
}); }
function publish() { if (!selected.value || dirty.value) return; void perform(async () => {
  const saved = await api<ActionLibraryEntry>('/action-library/' + selected.value!.id + '/publish', 'POST', { version: selected.value!.version });
  await refresh(); load(saved); message.value = text('版本已发布', 'Revision published');
}); }
function generate() { void perform(async () => {
  const saved = await api<ActionLibraryEntry>('/action-library/generate', 'POST', { domainCode: domain.value, ownerType: ownerType.value, modelId: modelId.value, description: request.value });
  await refresh(); load(saved); message.value = saved.source === 'MOCK' ? text('模拟模型生成的是固定模板，请编辑并试算。', 'The mock model returns a fixed template. Edit and preview it.') : text('LLM 草稿已生成，请审核、试算后发布。', 'LLM draft generated. Review and preview before publication.');
}); }
function test() { void perform(async () => { preview.value = await api('/action-library/preview', 'POST', { domainCode: domain.value, ownerType: ownerType.value, definition: definition(), worldId: worldId.value, targetId: targetId.value, parameters: JSON.parse(previewParameters.value) }); }); }
function upsert(w: World) { const index = worlds.value.findIndex(item => item.id === w.id); if (index >= 0) worlds.value[index] = w; }
function bind() { if (!selected.value || !world.value || !published.value) return; void perform(async () => {
  upsert(await api<World>('/action-library/' + selected.value!.id + '/bind', 'POST', { worldId: worldId.value, expectedVersion: world.value!.version }));
  message.value = text('已绑定到世界；新运行使用此定义，已有运行保留快照。请在场景中给 Agent 授权此 Action。', 'Bound to world for future runs. Existing runs keep their snapshots. Grant this Action to an agent in a scenario.');
}); }
async function openConfirmation(value: NonNullable<typeof confirmation.value>) { dialogTrigger = document.activeElement as HTMLElement; confirmation.value = value; await nextTick(); cancelButton.value?.focus(); }
function askDelete() { if (selected.value) void openConfirmation({ kind: 'draft', id: selected.value.id, version: selected.value.version }); }
function askUnbind(action: ActionDefinition) { if (world.value) void openConfirmation({ kind: 'binding', id: world.value.id, version: world.value.version, ownerType: ownerType.value, actionType: action.actionType }); }
async function cancel() { if (busy.value) return; confirmation.value = undefined; error.value = ''; await nextTick(); dialogTrigger?.focus(); }
function dialogKey(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); void cancel(); }
  if (event.key === 'Tab') { event.preventDefault(); const buttons = Array.from(cancelButton.value?.closest('[role="dialog"]')?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? []); const index = buttons.indexOf(document.activeElement as HTMLButtonElement); buttons[(index + (event.shiftKey ? -1 : 1) + buttons.length) % buttons.length]?.focus(); }
}
function confirm() { const c = confirmation.value; if (!c) return; void perform(async () => {
  if (c.kind === 'draft') { await api('/action-library/' + c.id + '?version=' + c.version, 'DELETE'); newDraft(); await refresh(); }
  else upsert(await api<World>('/action-library/unbind', 'POST', { worldId: c.id, ownerType: c.ownerType, actionType: c.actionType, expectedVersion: c.version }));
  confirmation.value = undefined; message.value = text('已删除', 'Removed'); await nextTick(); dialogTrigger?.focus();
}); }
const statusText = (e: ActionLibraryEntry) => (e.status === 'DRAFT' ? text('草稿', 'Draft') : text('已发布', 'Published')) + ' · ' +
  ({ BUILTIN: text('内置', 'Built-in'), MANUAL: text('手工维护', 'Manual'), MOCK: text('模拟模型', 'Mock model'), LLM: 'LLM' } as Record<string,string>)[e.source];
</script>

<template>
  <section class="space-y-5" :inert="confirmation ? true : undefined">
    <header><h2 class="text-xl font-semibold">{{ text('Action 维护', 'Action library') }}</h2><p class="mt-2 text-sm text-slate-600">{{ text('Action 定义在对象类型上。CEL 计算条件与参数，执行引擎原子执行声明步骤。草稿 → 校验与试算 → 发布 → 绑定世界 → 场景授权。', 'Actions belong to object types. CEL evaluates conditions and values; the engine commits declared operations atomically. Draft → validate and preview → publish → bind to a world → grant scenario permission.') }}</p></header>
    <p v-if="error" role="alert" class="text-red-700">{{ error }}</p><p v-if="message" role="status" class="text-emerald-800">{{ message }}</p>
    <div class="grid gap-5 xl:grid-cols-[minmax(240px,1fr)_minmax(0,3fr)]">
      <aside class="panel space-y-4 self-start">
        <label>{{ text('适用领域', 'Domain') }}<select v-model="domain" :disabled="busy || !!selected"><option value="information">{{ text('敏感信息泄露', 'Information leak') }}</option><option value="bank">{{ text('银行转账', 'Bank transfer') }}</option></select></label>
        <label>{{ text('搜索 Action', 'Search actions') }}<input v-model="search" /></label>
        <button type="button" class="secondary" :disabled="busy" @click="newDraft()">{{ text('新建 Action', 'New Action') }}</button>
        <article v-for="entry in filtered" :key="entry.id" class="border-t border-slate-200 pt-3 space-y-1">
          <p class="font-medium">{{ lang === 'zh' ? entry.definition.nameZh : entry.definition.nameEn }} · v{{ entry.revision }}</p><p class="text-xs break-all">{{ entry.ownerType }} · {{ entry.definition.actionType }}</p>
          <p class="text-xs text-slate-500">{{ statusText(entry) }}</p><button type="button" class="secondary" :disabled="busy" @click="load(entry)">{{ text('载入', 'Load') }}</button>
        </article>
      </aside>
      <div class="min-w-0 space-y-5">
        <form class="panel space-y-4" @submit.prevent="save">
          <div class="flex flex-wrap gap-3 items-center"><h3 class="font-semibold">{{ text('对象 Action 定义', 'Object Action definition') }}</h3><span v-if="selected" class="text-sm">{{ statusText(selected) }} · v{{ selected.revision }}</span></div>
          <p v-if="published" class="text-sm text-slate-600">{{ text('已发布版本不可修改。创建修订草稿后编辑，发布并重新绑定才会影响新运行。', 'Published revisions are immutable. Create a draft revision, then publish and rebind it to affect future runs.') }}</p>
          <fieldset :disabled="busy || published" class="space-y-4">
            <div class="grid gap-4 md:grid-cols-2"><label>{{ text('所属对象类型', 'Owner object type') }}<select v-model="ownerType" :disabled="!!selected"><option v-for="type in ownerTypes" :key="type">{{ type }}</option></select></label><label>{{ text('Action 编码', 'Action code') }}<input v-model="actionType" :disabled="!!selected" required pattern="[A-Z][A-Z0-9_]{0,63}" /></label></div>
            <div class="grid gap-4 md:grid-cols-2"><label>{{ text('中文名称', 'Chinese name') }}<input v-model="nameZh" required maxlength="100" /></label><label>{{ text('英文名称', 'English name') }}<input v-model="nameEn" required maxlength="100" /></label></div>
            <label class="block">{{ text('用途与执行效果', 'Purpose and effects') }}<textarea v-model="description" required maxlength="2000" rows="2" /></label>
            <section class="space-y-3"><h4 class="font-medium">{{ text('参数', 'Parameters') }}</h4>
              <div v-for="(p, i) in parameters" :key="i" class="rounded border border-slate-200 p-3 space-y-3">
                <div class="grid gap-3 md:grid-cols-3"><label>{{ text('参数编码', 'Parameter code') }} {{ i + 1 }}<input v-model="p.name" required /></label><label>{{ text('参数类型', 'Parameter type') }} {{ i + 1 }}<select v-model="p.type"><option value="string">{{ text('文本', 'String') }}</option><option value="integer">{{ text('整数', 'Integer') }}</option><option value="number">{{ text('数字', 'Number') }}</option><option value="boolean">{{ text('布尔值', 'Boolean') }}</option></select></label><label>{{ text('参数说明', 'Parameter description') }} {{ i + 1 }}<input v-model="p.description" /></label></div>
                <div class="flex flex-wrap gap-5"><label class="flex items-center gap-2"><input v-model="p.required" type="checkbox" class="w-auto" />{{ text('必填', 'Required') }}</label><label class="flex items-center gap-2"><input v-model="p.reference" type="checkbox" class="w-auto" />{{ text('关联对象 ID', 'Related object ID') }}</label></div>
                <label class="block">{{ text('可选对象列表（CEL，可选）', 'Object options (CEL, optional)') }} {{ i + 1 }}<input v-model="p.options" placeholder="entities.filter(e, e.id != target.id).map(e, e.id)" /></label>
                <label class="block">{{ text('枚举值（JSON 数组，可选）', 'Enum (JSON array, optional)') }} {{ i + 1 }}<input v-model="p.enumJson" /></label>
                <button type="button" class="secondary" @click="parameters.splice(i, 1)">{{ text('移除此参数', 'Remove parameter') }}</button>
              </div><button type="button" class="secondary" @click="parameters.push({ name: '', type: 'string', description: '', required: true, reference: false, options: '', enumJson: '' })">{{ text('增加参数', 'Add parameter') }}</button>
            </section>
            <section class="space-y-3"><h4 class="font-medium">{{ text('前置条件', 'Preconditions') }}</h4><p class="text-xs text-slate-500">{{ text('条件必须返回 true 才能执行；请填写条件不满足时的中文解释。', 'Conditions must return true. Supply an explanation for a failed condition.') }}</p>
              <div v-for="(c, i) in conditions" :key="i" class="rounded border border-slate-200 p-3 grid gap-3">
                <label>{{ text('条件表达式（CEL）', 'Condition (CEL)') }} {{ i + 1 }}<input v-model="c.expression" required /></label><label>{{ text('不满足条件时的中文提示', 'Chinese failure message') }} {{ i + 1 }}<input v-model="c.messageZh" required /></label><label>{{ text('不满足条件时的英文提示', 'English failure message') }} {{ i + 1 }}<input v-model="c.messageEn" required /></label>
                <button type="button" class="secondary justify-self-start" @click="conditions.splice(i, 1)">{{ text('移除此条件', 'Remove condition') }}</button>
              </div><button type="button" class="secondary" @click="conditions.push({ expression: 'true', messageZh: '', messageEn: '' })">{{ text('增加前置条件', 'Add precondition') }}</button>
            </section>
            <section class="space-y-3"><h4 class="font-medium">{{ text('声明式执行步骤', 'Declarative operations') }}</h4><p class="text-xs text-slate-500">{{ text('步骤按顺序执行；任一步失败会整体回滚。每个参数填写 CEL 表达式，固定文本需加单引号。余额修改必须使用转账操作。无执行步骤表示只读分析。', 'Operations run in order and roll back together on failure. Arguments are CEL expressions; quote literal strings. Balance changes require the transfer primitive. No operations means read-only analysis.') }}</p>
              <div v-for="(op, i) in operations" :key="i" class="rounded border border-slate-200 p-3 grid gap-3">
                <label>{{ text('执行操作', 'Operation') }} {{ i + 1 }}<select v-model="op.op" @change="operationDefaults(i)"><option value="SET_PROPERTY">{{ text('设置模拟属性', 'Set simulated property') }}</option><option value="DISABLE_RELATIONS">{{ text('撤销向关联对象的关系', 'Disable outgoing relationships') }}</option><option v-if="domain === 'bank' && ownerType === 'BANK_ACCOUNT'" value="TRANSFER_MONEY">{{ text('模拟转账（整数分结算）', 'Simulated transfer (integer minor units)') }}</option></select></label>
                <label v-for="key in args[op.op]" :key="key">{{ argName(key) }} {{ i + 1 }}<input v-model="op.arguments[key]" required maxlength="2048" /></label>
                <button type="button" class="secondary justify-self-start" @click="operations.splice(i, 1)">{{ text('移除此步骤', 'Remove operation') }}</button>
              </div><button type="button" class="secondary" @click="operations.push({ op: 'SET_PROPERTY', arguments: { object: 'target.id', property: &quot;'customScore'&quot;, value: '0' } })">{{ text('增加执行步骤', 'Add operation') }}</button>
            </section>
          </fieldset>
          <div class="flex flex-wrap gap-3"><button type="button" class="secondary" :disabled="busy" @click="validate">{{ text('校验定义', 'Validate definition') }}</button><button v-if="!published" class="primary" :disabled="busy">{{ text('保存草稿', 'Save draft') }}</button><button v-if="selected && !published" type="button" class="secondary" :disabled="busy || dirty" @click="publish">{{ text('发布此版本', 'Publish revision') }}</button><button v-if="selected" type="button" class="secondary" :disabled="busy" @click="newDraft(true)">{{ text('新建修订草稿', 'Create draft revision') }}</button><button v-if="selected && !published" type="button" class="secondary text-red-700" :disabled="busy" @click="askDelete">{{ text('删除草稿', 'Delete draft') }}</button></div>
          <p v-if="selected && dirty && !published" class="text-sm text-amber-800">{{ text('表单有未保存修改，请先保存再发布。', 'Save pending edits before publishing.') }}</p>
          <details><summary class="cursor-pointer text-sm">{{ text('CEL 变量与限制', 'CEL variables and limits') }}</summary><p class="mt-2 text-sm">{{ text('target：当前对象；params：输入参数；entities / relations：Agent 可见图谱；version：状态版本。支持 has、filter、map、exists。每条表达式最多 2048 字符，最多 8 条条件、16 个步骤、10 个参数。运行环境仅提供 JSON 值。', 'target: owner object; params: inputs; entities / relations: agent-visible graph; version: state version. Supports has, filter, map, exists. Limits: 2048 characters per expression, 8 conditions, 16 operations, 10 parameters. Runtime bindings contain JSON values only.') }}</p></details>
        </form>
        <form class="panel space-y-4" @submit.prevent="test"><h3 class="font-semibold">{{ text('试算与世界绑定', 'Preview and world binding') }}</h3><label class="block">{{ text('目标世界', 'Target world') }}<select v-model="worldId" required :disabled="busy"><option disabled value="">{{ text('请选择世界', 'Select a world') }}</option><option v-for="w in matchingWorlds" :key="w.id" :value="w.id">{{ w.name }} · v{{ w.version }}</option></select></label>
          <label class="block">{{ text('试算对象', 'Preview object') }}<select v-model="targetId" required :disabled="busy"><option v-for="entity in targets" :key="entity.id" :value="entity.id">{{ entity.name }} · {{ entity.id }}</option></select></label><label class="block">{{ text('试算参数（JSON）', 'Preview parameters (JSON)') }}<textarea v-model="previewParameters" rows="2" :disabled="busy" /></label>
          <p class="text-sm text-slate-600">{{ text('试算使用世界初始数据的独立副本，不保存状态。正式运行仍按各 Agent 的可见范围和权限校验。绑定仅影响之后创建的运行。', 'Preview uses an isolated copy of initial world data. Runtime visibility and permissions are still enforced for each agent. Binding affects future runs only.') }}</p>
          <div class="flex flex-wrap gap-3"><button class="secondary" :disabled="busy || !world || !targetId">{{ text('试算 Action', 'Preview Action') }}</button><button type="button" class="primary" :disabled="busy || !published || !world" @click="bind">{{ text('绑定到世界对象类型', 'Bind to world object type') }}</button></div>
          <div v-if="preview" role="status"><p :class="preview.accepted ? 'text-emerald-800' : 'text-red-700'">{{ preview.message }}</p><details><summary>{{ text('试算结果（未保存）', 'Preview result (not saved)') }}</summary><pre class="max-h-80 overflow-auto text-xs">{{ JSON.stringify(preview.state, null, 2) }}</pre></details></div>
          <section v-if="world" class="space-y-2"><h4 class="font-medium">{{ text('世界中当前类型已绑定的 Action', 'Actions currently bound to this world type') }}</h4><div v-for="a in boundActions" :key="a.actionType" class="flex flex-wrap items-center gap-3 text-sm"><span>{{ lang === 'zh' ? a.nameZh : a.nameEn }} · {{ a.actionType }}</span><button type="button" class="secondary text-red-700" :disabled="busy" @click="askUnbind(a)">{{ text('解除绑定', 'Unbind') }}</button></div></section>
        </form>
        <form class="panel space-y-4" @submit.prevent="generate"><h3 class="font-semibold">{{ text('LLM 生成 Action 草稿', 'Generate an Action draft with an LLM') }}</h3><p class="text-sm text-slate-600">{{ text('使用上方选择的领域与对象类型生成声明式定义。生成结果经过校验后仅保存为草稿，请审核并试算后发布、绑定。', 'Generate for the domain and owner type selected above. Validated output is saved as a draft only; review and preview before publishing and binding.') }}</p>
          <label class="block">{{ text('生成模型', 'Generation model') }}<select v-model="modelId" required :disabled="busy"><option disabled value="">{{ text('请选择已配置模型', 'Select a configured model') }}</option><option v-for="m in models" :key="m.id" :value="m.id">{{ m.name }} · {{ m.baseUrl.startsWith('mock:') ? 'MOCK' : 'LLM' }}</option></select></label>
          <p v-if="selectedModel?.baseUrl.startsWith('mock:')" class="text-sm text-amber-800">{{ text('模拟模型仅返回固定示例模板。生成自定义逻辑请选择真实 LLM。', 'The mock model returns a fixed template. Select a real LLM for custom logic.') }}</p>
          <label class="block">{{ text('Action 需求描述', 'Action requirements') }}<textarea v-model="request" required rows="3" maxlength="4000" :disabled="busy" /></label><button class="primary" :disabled="busy || !modelId || !request.trim()">{{ text('生成 Action 草稿', 'Generate Action draft') }}</button>
        </form>
      </div>
    </div>
  </section>
  <Teleport to="body"><div v-if="confirmation" class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-5" @keydown="dialogKey"><div role="dialog" aria-modal="true" :aria-label="text('确认删除', 'Confirm deletion')" class="panel w-full max-w-md"><p>{{ confirmation.kind === 'draft' ? text('确认删除此草稿？', 'Delete this draft?') : text('确认解除此世界对象类型上的 Action 绑定？已有运行保留原定义；新运行将不再拥有此绑定。', 'Unbind this Action from the world type? Existing runs retain their definitions; future runs will no longer have this binding.') }}</p><p v-if="error" role="alert">{{ error }}</p><div class="mt-5 flex justify-end gap-3"><button class="secondary text-red-700" :disabled="busy" @click="confirm">{{ text('确认删除', 'Confirm deletion') }}</button><button ref="cancelButton" class="secondary" :disabled="busy" @click="cancel">{{ text('取消', 'Cancel') }}</button></div></div></div></Teleport>
</template>

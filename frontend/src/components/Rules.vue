<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { api } from '../api';
import type { Lang, RuleSummary, World } from '../types';

const props = defineProps<{ lang: Lang; worlds: World[] }>();
const worldId = ref('');
const drafts = ref<RuleSummary[]>([]);
const draftId = ref('');
const definition = ref('{}');
const sampleEvent = ref('{"objectId":"","oldValue":null,"newValue":null}');
const preview = ref('');
const message = ref('');
const error = ref('');
const busy = ref(false);
const t = computed(() => props.lang === 'zh'
  ? { title: '触发规则', world: '世界', choose: '选择世界', new: '新建草稿', drafts: '草稿',
      definition: '规则定义（JSON）', event: '假设事件（JSON）', validate: '校验', preview: '试算',
      save: '保存草稿', publish: '发布版本', valid: '规则校验通过。', saved: '草稿已保存。',
      published: (n: number) => `已发布版本 ${n}。` }
  : { title: 'Trigger rules', world: 'World', choose: 'Select a world', new: 'New draft', drafts: 'Drafts',
      definition: 'Rule definition (JSON)', event: 'Hypothetical event (JSON)', validate: 'Validate', preview: 'Preview',
      save: 'Save draft', publish: 'Publish revision', valid: 'Rule is valid.', saved: 'Draft saved.',
      published: (n: number) => `Published revision ${n}.` });

function resetDraft() {
  draftId.value = '';
  preview.value = '';
  const world = props.worlds.find(item => item.id === worldId.value);
  const owner = world?.ontology?.types.find(type => type.actions.length);
  definition.value = JSON.stringify({
    name: '', scope: 'WORLD', enabled: true, priority: 0,
    trigger: { eventType: 'PROPERTY_CHANGED', objectType: owner?.type ?? '', property: '' },
    conditionExpression: 'true',
    actions: [{ actionType: owner?.actions[0]?.actionType ?? '', targetType: owner?.type ?? '',
      targetExpression: 'event.objectId', parameterExpressions: {}, delayExpression: '1' }],
    grant: { visibleEntityIds: [], targetTypes: owner ? [owner.type] : [],
      allowedActions: owner ? [owner.actions[0]!.actionType] : [] },
  }, null, 2);
}

watch(worldId, async id => {
  drafts.value = [];
  error.value = '';
  message.value = '';
  resetDraft();
  if (!id) return;
  try {
    const found = await api<RuleSummary[]>('/rules?worldId=' + encodeURIComponent(id));
    if (worldId.value === id) drafts.value = found;
  } catch (e) {
    if (worldId.value === id) error.value = String(e);
  }
});

function edit(rule: RuleSummary) {
  draftId.value = rule.id;
  definition.value = JSON.stringify(rule, null, 2);
  message.value = '';
  error.value = '';
  preview.value = '';
}

async function submit(kind: 'validate' | 'preview' | 'save' | 'publish') {
  if (busy.value || !worldId.value) return;
  busy.value = true;
  message.value = '';
  error.value = '';
  try {
    const rule = { ...JSON.parse(definition.value), worldId: worldId.value };
    if (kind === 'validate') {
      await api('/rules/validate', 'POST', rule);
      message.value = t.value.valid;
    } else if (kind === 'preview') {
      preview.value = '';
      const result = await api('/rules/preview', 'POST', { rule, event: JSON.parse(sampleEvent.value) });
      preview.value = JSON.stringify(result, null, 2);
    } else {
      const saved = await api<RuleSummary>(draftId.value ? '/rules/' + encodeURIComponent(draftId.value) : '/rules',
        draftId.value ? 'PUT' : 'POST', rule);
      drafts.value = [...drafts.value.filter(item => item.id !== saved.id), saved];
      edit(saved);
      if (kind === 'publish') {
        const published = await api<RuleSummary>('/rules/' + encodeURIComponent(saved.id) + '/publish', 'POST', {});
        message.value = t.value.published(published.revision);
      } else message.value = t.value.saved;
    }
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="panel space-y-4">
    <h2 class="text-xl font-semibold">{{ t.title }}</h2>
    <label>{{ t.world }}
      <select v-model="worldId" :disabled="busy">
        <option value="">{{ t.choose }}</option>
        <option v-for="world in worlds" :key="world.id" :value="world.id">{{ world.name }}</option>
      </select>
    </label>
    <fieldset v-if="worldId" :disabled="busy" class="grid gap-4">
      <div class="flex flex-wrap gap-2" :aria-label="t.drafts">
        <button class="secondary" type="button" @click="resetDraft">{{ t.new }}</button>
        <button v-for="rule in drafts" :key="rule.id" class="secondary" type="button"
          :aria-pressed="draftId === rule.id" @click="edit(rule)">{{ rule.name }}</button>
      </div>
      <label>{{ t.definition }}<textarea v-model="definition" rows="16" class="font-mono text-sm" /></label>
      <label>{{ t.event }}<textarea v-model="sampleEvent" rows="3" class="font-mono text-sm" /></label>
      <div class="flex flex-wrap gap-2">
        <button class="secondary" type="button" @click="submit('validate')">{{ t.validate }}</button>
        <button class="secondary" type="button" @click="submit('preview')">{{ t.preview }}</button>
        <button class="primary" type="button" @click="submit('save')">{{ t.save }}</button>
        <button class="secondary" type="button" @click="submit('publish')">{{ t.publish }}</button>
      </div>
    </fieldset>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="error" role="alert" class="text-red-700">{{ error }}</p>
    <pre v-if="preview" class="overflow-auto whitespace-pre-wrap rounded bg-slate-50 p-3 text-sm">{{ preview }}</pre>
  </section>
</template>

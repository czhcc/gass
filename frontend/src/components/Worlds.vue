<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, ref } from "vue";
import { storeToRefs } from "pinia";
import { api, ApiError } from "../api";
import { useWorkspaceStore } from "../stores/workspace";
import EntityDetails from "./EntityDetails.vue";
import type { Lang, Ontology, World } from "../types";

const props = defineProps<{ lang: Lang }>();
const Graph = defineAsyncComponent(() => import("./Graph.vue"));
const { worlds } = storeToRefs(useWorkspaceStore());
const selectedId = ref(""), search = ref(""), domainFilter = ref("");
const selected = computed(() => worlds.value.find(w => w.id === selectedId.value));
const selectedObject = ref<{ worldId: string; entityId: string }>();
const selectedEntity = computed(() => selectedObject.value?.worldId === selected.value?.id
  ? selected.value?.entities.find(e => e.id === selectedObject.value?.entityId) : undefined);
const visible = computed(() => worlds.value.filter(w =>
  (!domainFilter.value || w.domainCode === domainFilter.value) &&
  (w.name + " " + w.id).toLowerCase().includes(search.value.toLowerCase()),
));
const initial = (id: string) => ["demo-information", "demo-bank"].includes(id);
const busy = ref(false), error = ref(""), message = ref("");
const editing = ref(false), editingId = ref<string | null>(null), importing = ref(false);
const sourceJson = ref(""), entityJson = ref("[]"), relationJson = ref("[]");
const name = ref(""), domain = ref("information"), entityTypes = ref(""), relationTypes = ref("");
const version = ref(1), editor = ref<HTMLElement>(), list = ref<HTMLElement>();
const ontology = ref<Ontology | null>();
const deletionId = ref<string | null>(null), deletionError = ref("");
const dialog = ref<HTMLDivElement>(), cancelButton = ref<HTMLButtonElement>();
let deletionTrigger: HTMLElement | null = null;
const t = computed(() => props.lang === "zh" ? {
  title: "世界管理", intro: "世界只维护初始对象、关系和状态；Agent 与模型在场景中配置。两份初始世界可重复使用，创建演示不会新增世界。",
  create: "创建世界", search: "搜索世界", filter: "世界类型筛选", all: "全部类型",
  information: "泄密", bank: "银行", builtin: "初始世界", custom: "自定义世界",
  empty: "没有匹配的世界", choose: "选择世界查看图谱和初始数据。", edit: "编辑世界", copy: "复制世界", copySuffix: "副本",
  remove: "删除世界", export: "导出 JSON", import: "导入 JSON", load: "载入编辑器", fullJson: "世界定义 JSON",
  importHelp: "粘贴导出的完整世界 JSON；载入后检查并保存为新世界，不会覆盖原世界。",
  name: "世界名称", domain: "世界类型", entityTypes: "对象类型（逗号分隔）", relationTypes: "关系类型（逗号分隔）",
  entities: "对象 JSON", relations: "关系 JSON", save: "保存世界", cancel: "取消", saved: "世界已保存", copied: "世界已复制", deleted: "世界已删除",
  confirm: "确认删除", deleteHelp: "确认删除此世界？若有场景引用，请先调整或删除相关场景。已有运行历史保留。",
  protected: "初始世界可编辑、复制和导出，不能删除。", version: "版本", objects: "对象", edges: "关系",
  editorHelp: "对象和关系使用 JSON 数组维护。编辑仅影响之后创建的运行；被场景引用的对象不能直接移除。",
  jsonInvalid: "请提供有效的世界定义及对象、关系 JSON 数组。",
  stale: "世界已被修改，请重新选择并载入最新版本后再编辑。",
  inUse: "此世界正被场景引用，请先调整或删除相关场景。",
  typeActions: "类型行动定义", type: "类型", kind: "作用目标", entity: "对象", relation: "关系", noActions: "此类型未定义行动",
  actionHelp: "行动只属于对象类型，关联对象通过参数指定，关系由执行引擎维护。Agent 在场景中获得行动权限，定义随世界导出并在运行时冻结。",
} : {
  title: "World management", intro: "Worlds define initial objects, relationships and state. Agents and models belong to scenarios. Reuse the two initial worlds; demos do not add worlds.",
  create: "Create world", search: "Search worlds", filter: "Filter world type", all: "All types",
  information: "Information leak", bank: "Bank", builtin: "Initial world", custom: "Custom world",
  empty: "No matching worlds", choose: "Select a world to view its graph and initial data.", edit: "Edit world", copy: "Copy world", copySuffix: "copy",
  remove: "Delete world", export: "Export JSON", import: "Import JSON", load: "Load into editor", fullJson: "World definition JSON",
  importHelp: "Paste a complete exported world JSON. Review and save it as a new world without overwriting the source.",
  name: "World name", domain: "World type", entityTypes: "Entity types (comma separated)", relationTypes: "Relation types (comma separated)",
  entities: "Entities JSON", relations: "Relations JSON", save: "Save world", cancel: "Cancel", saved: "World saved", copied: "World copied", deleted: "World deleted",
  confirm: "Confirm deletion", deleteHelp: "Delete this world? Change or delete scenarios referencing it first. Existing run history will be retained.",
  protected: "Initial worlds can be edited, copied and exported, but cannot be deleted.", version: "Version", objects: "Objects", edges: "Relations",
  editorHelp: "Maintain objects and relationships as JSON arrays. Edits affect future runs only. Objects referenced by a scenario cannot be removed.",
  jsonInvalid: "Provide a valid world definition and JSON arrays for entities and relationships.",
  stale: "The world changed. Select it again and load the latest version before editing.",
  inUse: "This world is used by a scenario. Change or delete the referencing scenarios first.",
  typeActions: "Type action definitions", type: "Type", kind: "Target kind", entity: "Entity", relation: "Relation", noActions: "No actions defined for this type",
  actionHelp: "Actions belong only to entity types. Parameters identify related objects; the engine maintains relations. Contracts are exported with worlds and frozen in runs.",
});

function errorText(e: unknown) {
  if (e instanceof SyntaxError) return t.value.jsonInvalid;
  if (e instanceof ApiError) {
    if (e.code === "WORLD_STALE") return t.value.stale;
    if (e.code === "WORLD_IN_USE") return t.value.inUse;
    if (e.code === "WORLD_INITIAL_PROTECTED") return t.value.protected;
  }
  return String(e);
}

function selectWorld(id: string) {
  selectedId.value = id;
  editing.value = false;
  importing.value = false;
  error.value = "";
  message.value = "";
}
async function openEditor(w: World, id: string | null) {
  editingId.value = id;
  name.value = id ? w.name : "";
  domain.value = w.domainCode;
  version.value = w.version ?? 1;
  entityTypes.value = w.entityTypes.join(", ");
  relationTypes.value = w.relationTypes.join(", ");
  entityJson.value = JSON.stringify(w.entities, null, 2);
  relationJson.value = JSON.stringify(w.relations, null, 2);
  ontology.value = w.ontology;
  editing.value = true;
  importing.value = false;
  error.value = "";
  message.value = "";
  await nextTick();
  editor.value?.querySelector<HTMLInputElement>("input")?.focus();
}
async function createWorld() {
  const seed = worlds.value.find(w => w.id === "demo-information") ?? worlds.value[0];
  if (seed) await openEditor(seed, null);
}
function changeDomain() {
  const seed = worlds.value.find(w => w.id === "demo-" + domain.value);
  if (seed) {
    ontology.value = seed.ontology;
    entityTypes.value = seed.entityTypes.join(", ");
    relationTypes.value = seed.relationTypes.join(", ");
    entityJson.value = JSON.stringify(seed.entities, null, 2);
    relationJson.value = JSON.stringify(seed.relations, null, 2);
  }
}
function upsert(w: World) {
  const index = worlds.value.findIndex(item => item.id === w.id);
  if (index < 0) worlds.value.push(w);
  else worlds.value[index] = w;
  selectedId.value = w.id;
}
async function saveWorld() {
  if (busy.value) return;
  error.value = "";
  try {
    const entities = JSON.parse(entityJson.value), relations = JSON.parse(relationJson.value);
    if (!Array.isArray(entities) || !Array.isArray(relations)) throw new Error(t.value.jsonInvalid);
    const split = (value: string) => value.split(",").map(part => part.trim()).filter(Boolean);
    const body = { name: name.value.trim(), domainCode: domain.value, version: version.value,
      entityTypes: split(entityTypes.value), relationTypes: split(relationTypes.value), entities, relations,
      ontology: ontology.value };
    busy.value = true;
    const w = await api<World>(editingId.value ? "/worlds/" + editingId.value : "/worlds",
      editingId.value ? "PUT" : "POST", body);
    upsert(w);
    editing.value = false;
    message.value = t.value.saved;
  } catch (e) { error.value = errorText(e); }
  finally { busy.value = false; }
}
async function copyWorld() {
  if (!selected.value || busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const suffix = " " + t.value.copySuffix;
    upsert(await api<World>("/worlds/" + selected.value.id + "/copy", "POST", { name: selected.value.name.slice(0, 200 - suffix.length) + suffix }));
    message.value = t.value.copied;
  } catch (e) { error.value = errorText(e); }
  finally { busy.value = false; }
}
async function exportWorld() {
  if (!selected.value || busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const w = await api<World>("/worlds/" + selected.value.id + "/export");
    const url = URL.createObjectURL(new Blob([JSON.stringify(w, null, 2)], { type: "application/json" }));
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = w.name.replace(/[<>:"/\\|?*]/g, "_") + ".json";
    anchor.click();
    URL.revokeObjectURL(url);
  } catch (e) { error.value = errorText(e); }
  finally { busy.value = false; }
}
async function importWorld() {
  try {
    const w = JSON.parse(sourceJson.value) as World;
    if (!["information", "bank"].includes(w.domainCode) || !Array.isArray(w.entities) ||
        !Array.isArray(w.relations) || !Array.isArray(w.entityTypes) || !Array.isArray(w.relationTypes) || typeof w.name !== "string")
      throw new Error(t.value.jsonInvalid);
    await openEditor(w, null);
    name.value = w.name;
  } catch (e) { error.value = errorText(e); }
}
async function openDeletion(event: MouseEvent) {
  if (!selected.value || initial(selected.value.id)) return;
  deletionTrigger = event.currentTarget as HTMLElement;
  deletionId.value = selected.value.id;
  deletionError.value = "";
  await nextTick();
  cancelButton.value?.focus();
}
async function closeDeletion() {
  if (busy.value) return;
  deletionId.value = null;
  await nextTick();
  deletionTrigger?.focus();
}
function dialogKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") { event.preventDefault(); void closeDeletion(); }
  if (event.key === "Tab") {
    event.preventDefault();
    const buttons = Array.from(dialog.value?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)") ?? []);
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
    if (buttons.length) buttons[(index + (event.shiftKey ? -1 : 1) + buttons.length) % buttons.length]?.focus();
  }
}
async function deleteWorld() {
  if (!deletionId.value || busy.value) return;
  busy.value = true;
  deletionError.value = "";
  try {
    await api<void>("/worlds/" + deletionId.value, "DELETE");
    worlds.value = worlds.value.filter(w => w.id !== deletionId.value);
    selectedId.value = "";
    deletionId.value = null;
    message.value = t.value.deleted;
    await nextTick();
    list.value?.focus();
  } catch (e) { deletionError.value = errorText(e); }
  finally { busy.value = false; }
}
</script>

<template>
  <section :inert="deletionId !== null ? true : undefined" class="space-y-5">
    <div class="flex flex-wrap items-start justify-between gap-4">
      <div><h2 class="text-xl font-semibold">{{ t.title }}</h2><p class="mt-2 max-w-3xl text-sm text-slate-500">{{ t.intro }}</p></div>
      <div class="flex gap-2">
        <button class="primary" :disabled="busy" @click="createWorld">{{ t.create }}</button>
        <button class="secondary" :disabled="busy" @click="importing = true; editing = false; error = ''">{{ t.import }}</button>
      </div>
    </div>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
    <form v-if="importing" class="panel space-y-4" @submit.prevent="importWorld">
      <p class="text-sm text-slate-500">{{ t.importHelp }}</p>
      <label>{{ t.fullJson }}<textarea v-model="sourceJson" rows="10" required class="font-mono text-xs" /></label>
      <div class="flex gap-2"><button class="primary">{{ t.load }}</button><button type="button" class="secondary" @click="importing = false">{{ t.cancel }}</button></div>
    </form>
    <form v-if="editing" ref="editor" class="panel space-y-4" @submit.prevent="saveWorld">
      <p class="text-sm text-slate-500">{{ t.editorHelp }}</p>
      <fieldset :disabled="busy" class="grid gap-4 md:grid-cols-2">
        <label>{{ t.name }}<input v-model="name" required maxlength="200" /></label>
        <label>{{ t.domain }}<select v-model="domain" :disabled="editingId !== null" @change="changeDomain"><option value="information">{{ t.information }}</option><option value="bank">{{ t.bank }}</option></select></label>
        <label>{{ t.entityTypes }}<input v-model="entityTypes" required /></label>
        <label>{{ t.relationTypes }}<input v-model="relationTypes" /></label>
        <label>{{ t.entities }}<textarea v-model="entityJson" rows="12" required class="font-mono text-xs" /></label>
        <label>{{ t.relations }}<textarea v-model="relationJson" rows="12" required class="font-mono text-xs" /></label>
      </fieldset>
      <div class="flex gap-2"><button class="primary" :disabled="busy">{{ t.save }}</button><button type="button" class="secondary" :disabled="busy" @click="editing = false; error = ''">{{ t.cancel }}</button></div>
    </form>
    <div class="grid gap-5 lg:grid-cols-[300px_1fr]">
      <aside ref="list" class="panel space-y-4" tabindex="-1">
        <label>{{ t.search }}<input v-model="search" type="search" /></label>
        <label>{{ t.filter }}<select v-model="domainFilter"><option value="">{{ t.all }}</option><option value="information">{{ t.information }}</option><option value="bank">{{ t.bank }}</option></select></label>
        <ul class="space-y-3">
          <li v-for="w in visible" :key="w.id" class="rounded-lg border p-3" :class="selectedId === w.id ? 'border-blue-500 bg-blue-50' : 'border-slate-200'">
            <button class="break-words text-left font-medium text-blue-700" :disabled="busy" @click="selectWorld(w.id)">{{ w.name }}</button>
            <p class="mt-2 text-xs text-slate-500">{{ w.domainCode === 'bank' ? t.bank : t.information }} · {{ initial(w.id) ? t.builtin : t.custom }} · {{ t.version }} {{ w.version }}</p>
            <p class="mt-1 text-xs text-slate-500">{{ w.entities.length }} {{ t.objects }} · {{ w.relations.length }} {{ t.edges }}</p>
          </li>
        </ul>
        <p v-if="!visible.length" class="text-sm text-slate-500">{{ t.empty }}</p>
      </aside>
      <article class="panel min-w-0">
        <template v-if="selected">
          <h3 class="text-lg font-semibold">{{ selected.name }}</h3>
          <div class="my-4 flex flex-wrap gap-2">
            <button class="secondary" :disabled="busy" @click="openEditor(selected, selected.id)">{{ t.edit }}</button>
            <button class="secondary" :disabled="busy" @click="copyWorld">{{ t.copy }}</button>
            <button class="secondary" :disabled="busy" @click="exportWorld">{{ t.export }}</button>
            <button class="secondary text-red-700" :disabled="busy || initial(selected.id)" @click="openDeletion">{{ t.remove }}</button>
          </div>
          <p v-if="initial(selected.id)" class="mb-4 text-xs text-slate-500">{{ t.protected }}</p>
          <Graph :world="selected" :lang="lang" @select="id => selectedObject = { worldId: selected!.id, entityId: id }" />
          <EntityDetails v-if="selectedEntity" :entity="selectedEntity" :lang="lang" :ontology="selected.ontology" />
          <section v-if="selected.ontology" class="mt-5 space-y-3">
            <h4 class="font-semibold">{{ t.typeActions }}</h4>
            <p class="text-xs text-slate-500">{{ t.actionHelp }}</p>
            <div class="overflow-x-auto">
              <table class="w-full text-left text-sm">
                <thead><tr><th class="p-2">{{ t.kind }}</th><th class="p-2">{{ t.type }}</th><th class="p-2">Action</th></tr></thead>
                <tbody><tr v-for="type in selected.ontology.types.filter(type => type.targetKind === 'ENTITY' && selected!.entityTypes.includes(type.type))" :key="type.targetKind + type.type" class="border-t border-slate-100 align-top">
                  <td class="p-2">{{ type.targetKind === 'ENTITY' ? t.entity : t.relation }}</td><td class="p-2 font-mono text-xs">{{ type.type }}</td>
                  <td class="p-2"><details v-for="action in type.actions" :key="action.actionType" class="mb-2"><summary class="cursor-pointer">{{ lang === 'zh' ? action.nameZh : action.nameEn }} · {{ action.actionType }}</summary><pre class="mt-2 max-h-48 overflow-auto whitespace-pre-wrap text-xs">{{ JSON.stringify(action, null, 2) }}</pre></details><span v-if="!type.actions.length">{{ t.noActions }}</span></td>
                </tr></tbody>
              </table>
            </div>
          </section>
          <details class="mt-4"><summary>{{ t.fullJson }}</summary><pre class="mt-3 max-h-80 overflow-auto whitespace-pre-wrap break-all text-xs">{{ JSON.stringify(selected, null, 2) }}</pre></details>
        </template>
        <p v-else class="text-sm text-slate-500">{{ t.choose }}</p>
      </article>
    </div>
  </section>
  <Teleport to="body">
    <div v-if="deletionId !== null" class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-5" @click.self="closeDeletion">
      <div ref="dialog" role="dialog" aria-modal="true" aria-labelledby="world-delete-title" aria-describedby="world-delete-description" class="panel w-full max-w-md" @keydown="dialogKeydown">
        <h2 id="world-delete-title" class="text-lg font-semibold">{{ t.remove }}</h2>
        <p id="world-delete-description" class="mt-3 text-sm text-slate-600">{{ t.deleteHelp }}</p>
        <p v-if="deletionError" role="alert">{{ deletionError }}</p>
        <div class="mt-6 flex justify-end gap-3">
          <button class="secondary text-red-700" :disabled="busy" @click="deleteWorld">{{ t.confirm }}</button>
          <button ref="cancelButton" class="secondary" :disabled="busy" @click="closeDeletion">{{ t.cancel }}</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

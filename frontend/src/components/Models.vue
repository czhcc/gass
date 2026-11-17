<script setup lang="ts">
import { ref, onMounted, computed, nextTick } from "vue";
import { api } from "../api";
import type { Lang, Model } from "../types";
const props = defineProps<{ lang: Lang }>();
const models = ref<Model[]>([]),
  busy = ref(false),
  loading = ref(true),
  message = ref(""),
  error = ref(""),
  editingId = ref<string | null>(null),
  highlightedId = ref(""),
  testingId = ref<string | null>(null);
const results = ref<Record<string, { ok: boolean; text: string }>>({});
const editorHeading = ref<HTMLHeadingElement>();
const defaults = () => ({
  name: "",
  baseUrl: "",
  modelName: "",
  apiKey: "",
  apiKeyEnv: "",
  temperature: 0.2,
  maxTokens: 1024,
  timeoutSeconds: 30,
  concurrency: 1,
});
const form = ref(defaults());
const t = computed(() =>
  props.lang === "zh"
    ? {
        title: "模型配置",
        mock: "模拟模型",
        useMock: "使用模拟模型",
        savedModels: "已保存的模型",
        empty: "尚未保存模型。可使用模拟模型，或在下方添加模型配置。",
        loading: "正在读取已保存的模型…",
        add: "新增模型",
        edit: "编辑",
        editing: "编辑模型",
        cancel: "取消编辑",
        name: "名称",
        model: "模型名称",
        save: "保存模型",
        update: "保存更改",
        test: "测试连接",
        testing: "测试中…",
        ok: "连接成功",
        failed: "模型连接失败，请检查地址、密钥和工具调用兼容性",
        secret:
          "API Key 加密保存且不会回传。也可使用环境变量；本地免密服务可留空。",
        retain: "API Key 留空将保留已保存的密钥；填写环境变量可切换密钥来源。",
        env: "API Key 环境变量",
        temp: "Temperature",
        tokens: "最大输出 Token",
        timeout: "超时（秒）",
        concurrency: "并发限制",
        saved: "已保存",
        configured: "密钥已配置",
        none: "未配置密钥",
        simulated: "模拟生成 · 不调用外部 LLM",
      }
    : {
        title: "Model configuration",
        mock: "Simulated model",
        useMock: "Use simulated model",
        savedModels: "Saved models",
        empty:
          "No saved models. Use the simulated model or add a configuration below.",
        loading: "Loading saved models…",
        add: "Add model",
        edit: "Edit",
        editing: "Edit model",
        cancel: "Cancel editing",
        name: "Name",
        model: "Model name",
        save: "Save model",
        update: "Save changes",
        test: "Test connection",
        testing: "Testing…",
        ok: "Connection successful",
        failed:
          "Model connection failed; check endpoint, key and tool compatibility",
        secret:
          "API keys are encrypted and never returned. Alternatively reference an environment variable; leave blank for a local keyless service.",
        retain:
          "Leave API Key blank to retain the saved key. Enter an environment variable to switch credential sources.",
        env: "API Key environment variable",
        temp: "Temperature",
        tokens: "Max output tokens",
        timeout: "Timeout (seconds)",
        concurrency: "Concurrency",
        saved: "Saved",
        configured: "Key configured",
        none: "No key configured",
        simulated: "SIMULATED · no external LLM",
      },
);
onMounted(async () => {
  try {
    const stored = await api<Model[]>("/models");
    // A slow initial read must not overwrite entries saved or edited while it was in flight.
    models.value = [
      ...new Map(
        [...stored, ...models.value].map((model) => [model.id, model]),
      ).values(),
    ];
  } catch (e) {
    error.value = String(e);
  } finally {
    loading.value = false;
  }
});
function upsert(model: Model) {
  const index = models.value.findIndex((m) => m.id === model.id);
  if (index < 0) models.value.push(model);
  else models.value[index] = model;
  highlightedId.value = model.id;
}
async function reveal(id: string) {
  await nextTick();
  const card = document.getElementById("saved-model-" + id);
  card?.scrollIntoView?.({ behavior: "smooth", block: "nearest" });
  card?.focus({ preventScroll: true });
}
async function save() {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    const id = editingId.value;
    const model = await api<Model>(
      id ? "/models/" + id : "/models",
      id ? "PUT" : "POST",
      form.value,
    );
    upsert(model);
    delete results.value[model.id];
    form.value.apiKey = "";
    message.value = model.name + " · " + t.value.saved;
    editingId.value = null;
    form.value = defaults();
    await reveal(model.id);
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function edit(model: Model) {
  editingId.value = model.id;
  message.value = "";
  error.value = "";
  form.value = {
    name: model.name,
    baseUrl: model.baseUrl,
    modelName: model.modelName,
    apiKey: "",
    apiKeyEnv: model.apiKeyEnv ?? "",
    temperature: model.temperature ?? 0.2,
    maxTokens: model.maxTokens ?? 1024,
    timeoutSeconds: model.timeoutSeconds ?? 30,
    concurrency: model.concurrency ?? 1,
  };
  await nextTick();
  editorHeading.value?.scrollIntoView?.({ behavior: "smooth", block: "start" });
  editorHeading.value?.focus({ preventScroll: true });
}
function cancel() {
  editingId.value = null;
  form.value = defaults();
  error.value = "";
  message.value = "";
}
async function test(id: string) {
  busy.value = true;
  testingId.value = id;
  error.value = "";
  delete results.value[id];
  try {
    await api("/models/" + id + "/test", "POST");
    results.value[id] = { ok: true, text: t.value.ok };
  } catch {
    results.value[id] = { ok: false, text: t.value.failed };
  } finally {
    busy.value = false;
    testingId.value = null;
  }
}
async function useMock() {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    const model = await api<Model>("/models/mock", "POST", {});
    upsert(model);
    message.value = model.name + " · " + t.value.saved;
    await reveal(model.id);
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="space-y-6">
    <h2 class="text-xl font-semibold">{{ t.title }}</h2>
    <section class="panel" aria-labelledby="mock-model-heading">
      <h3 id="mock-model-heading" class="mb-4 font-semibold">{{ t.mock }}</h3>
      <button class="secondary" type="button" :disabled="busy" @click="useMock">
        {{ t.useMock }}
      </button>
    </section>
    <section class="panel" aria-labelledby="saved-models-heading">
      <h3
        id="saved-models-heading"
        :aria-label="t.savedModels"
        class="font-semibold"
      >
        {{ t.savedModels }}
        <span class="text-sm font-normal text-slate-500"
          >({{ models.length }})</span
        >
      </h3>
      <p v-if="loading" class="mt-3 text-sm text-slate-500">{{ t.loading }}</p>
      <p v-else-if="!models.length" class="mt-3 text-sm text-slate-500">
        {{ t.empty }}
      </p>
      <p v-if="message" role="status" class="mt-4 text-sm text-green-700">
        {{ message }}
      </p>
      <div class="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        <article
          v-for="model in models"
          :id="'saved-model-' + model.id"
          :key="model.id"
          :aria-label="model.name"
          tabindex="-1"
          class="min-w-0 rounded-xl border p-4"
          :class="
            highlightedId === model.id
              ? 'border-blue-500 bg-blue-50/40'
              : 'border-slate-200'
          "
        >
          <h4 class="break-words font-semibold">{{ model.name }}</h4>
          <p
            v-if="model.baseUrl.startsWith('mock:')"
            class="my-2 text-xs font-semibold text-amber-700"
          >
            {{ t.simulated }}
          </p>
          <p class="mt-2 break-all text-sm">{{ model.modelName }}</p>
          <p class="mt-1 break-all text-xs text-slate-500">
            {{ model.baseUrl }}
          </p>
          <p class="my-3 text-xs">
            {{ model.keyConfigured ? t.configured : t.none }}
          </p>
          <dl class="mb-3 grid grid-cols-2 gap-2 text-xs text-slate-600">
            <div>
              <dt>{{ t.temp }}</dt>
              <dd>{{ model.temperature ?? 0.2 }}</dd>
            </div>
            <div>
              <dt>{{ t.tokens }}</dt>
              <dd>{{ model.maxTokens ?? 1024 }}</dd>
            </div>
            <div>
              <dt>{{ t.timeout }}</dt>
              <dd>{{ model.timeoutSeconds ?? 30 }}</dd>
            </div>
            <div>
              <dt>{{ t.concurrency }}</dt>
              <dd>{{ model.concurrency ?? 1 }}</dd>
            </div>
          </dl>
          <p class="mb-4 break-all font-mono text-xs text-slate-400">
            ID: {{ model.id }}
          </p>
          <div class="flex flex-wrap gap-2">
            <button class="secondary" :disabled="busy" @click="test(model.id)">
              {{ testingId === model.id ? t.testing : t.test }}</button
            ><button class="secondary" :disabled="busy" @click="edit(model)">
              {{ t.edit }}
            </button>
          </div>
          <p
            v-if="results[model.id]"
            :role="results[model.id].ok ? 'status' : 'alert'"
            class="mt-3 text-sm"
            :class="results[model.id].ok ? 'text-green-700' : 'text-red-700'"
          >
            {{ results[model.id].text }}
          </p>
        </article>
      </div>
    </section>
    <section class="panel" aria-labelledby="model-editor-heading">
      <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
        <h3
          id="model-editor-heading"
          ref="editorHeading"
          tabindex="-1"
          class="font-semibold"
        >
          {{ editingId ? t.editing : t.add }}
        </h3>
        <button
          v-if="editingId"
          class="secondary"
          :disabled="busy"
          @click="cancel"
        >
          {{ t.cancel }}
        </button>
      </div>
      <form class="grid gap-4" @submit.prevent="save">
        <fieldset :disabled="busy" class="grid gap-4">
          <div class="grid gap-4 md:grid-cols-2">
            <label
              >{{ t.name
              }}<input v-model="form.name" required maxlength="100" /></label
            ><label
              >Base URL<input
                v-model="form.baseUrl"
                type="url"
                placeholder="https://provider.example/v1"
                required /></label
            ><label
              >{{ t.model }}<input v-model="form.modelName" required /></label
            ><label
              >API Key<input
                v-model="form.apiKey"
                type="password"
                autocomplete="new-password"
            /></label>
          </div>
          <label
            >{{ t.env
            }}<input
              v-model="form.apiKeyEnv"
              placeholder="STUDIO_LLM_API_KEY"
              pattern="[A-Z_][A-Z0-9_]*"
          /></label>
          <p class="text-xs text-slate-500">
            {{ editingId ? t.retain : t.secret }}
          </p>
          <div class="grid grid-cols-2 gap-4 lg:grid-cols-4">
            <label
              >{{ t.temp
              }}<input
                v-model.number="form.temperature"
                type="number"
                min="0"
                max="2"
                step="0.1" /></label
            ><label
              >{{ t.tokens
              }}<input
                v-model.number="form.maxTokens"
                type="number"
                min="64"
                max="1024" /></label
            ><label
              >{{ t.timeout
              }}<input
                v-model.number="form.timeoutSeconds"
                type="number"
                min="1"
                max="60" /></label
            ><label
              >{{ t.concurrency
              }}<input
                v-model.number="form.concurrency"
                type="number"
                min="1"
                max="8"
            /></label>
          </div>
          <button class="primary justify-self-start">
            {{ editingId ? t.update : t.save }}
          </button>
        </fieldset>
      </form>
      <p v-if="error" role="alert">{{ error }}</p>
    </section>
  </section>
</template>

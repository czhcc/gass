<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import { api } from "../api";
import type { Lang, Model } from "../types";
const props = defineProps<{ lang: Lang }>();
const models = ref<Model[]>([]),
  busy = ref(false),
  message = ref(""),
  error = ref("");
const form = ref({
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
const t = computed(() =>
  props.lang === "zh"
    ? {
        title: "模型配置",
        name: "名称",
        model: "模型名称",
        save: "保存模型",
        test: "测试连接",
        ok: "连接成功",
        failed: "模型连接失败，请检查地址、密钥和工具调用兼容性",
        secret:
          "API Key 加密保存且不会回传。也可使用环境变量；本地免密服务可留空。",
        env: "API Key 环境变量",
        temp: "Temperature",
        tokens: "最大输出 Token",
        timeout: "超时（秒）",
        concurrency: "并发限制",
        saved: "已保存",
        configured: "密钥已配置",
        none: "未配置密钥",
      }
    : {
        title: "Model configuration",
        name: "Name",
        model: "Model name",
        save: "Save model",
        test: "Test connection",
        ok: "Connection successful",
        failed:
          "Model connection failed; check endpoint, key and tool compatibility",
        secret:
          "API keys are encrypted and never returned. Alternatively reference an environment variable; leave blank for a local keyless service.",
        env: "API Key environment variable",
        temp: "Temperature",
        tokens: "Max output tokens",
        timeout: "Timeout (seconds)",
        concurrency: "Concurrency",
        saved: "Saved",
        configured: "Key configured",
        none: "No key configured",
      },
);
onMounted(async () => {
  try {
    models.value = await api<Model[]>("/models");
  } catch (e) {
    error.value = String(e);
  }
});
async function save() {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    const m = await api<Model>("/models", "POST", form.value);
    models.value.push(m);
    form.value.apiKey = "";
    message.value = t.value.saved;
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function test(id: string) {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    await api("/models/" + id + "/test", "POST");
    message.value = t.value.ok;
  } catch {
    error.value = t.value.failed;
  } finally {
    busy.value = false;
  }
}
async function useMock() {
  busy.value = true;
  error.value = "";
  try {
    const model = await api<Model>("/models/mock", "POST", {});
    if (!models.value.some((m) => m.id === model.id)) models.value.push(model);
    message.value = t.value.saved;
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="grid gap-6 lg:grid-cols-2">
    <div class="panel">
      <h2 class="mb-5 text-xl font-semibold">{{ t.title }}</h2>
      <button
        class="secondary mb-5"
        type="button"
        :disabled="busy"
        @click="useMock"
      >
        {{ lang === "zh" ? "使用模拟模型" : "Use simulated model" }}
      </button>
      <form class="grid gap-4" @submit.prevent="save">
        <label
          >{{ t.name
          }}<input v-model="form.name" required maxlength="100" /></label
        ><label
          >Base URL<input
            v-model="form.baseUrl"
            type="url"
            placeholder="https://provider.example/v1"
            required /></label
        ><label>{{ t.model }}<input v-model="form.modelName" required /></label
        ><label
          >API Key<input
            v-model="form.apiKey"
            type="password"
            autocomplete="new-password" /></label
        ><label
          >{{ t.env
          }}<input
            v-model="form.apiKeyEnv"
            placeholder="STUDIO_LLM_API_KEY"
            pattern="[A-Z_][A-Z0-9_]*"
        /></label>
        <p class="text-xs text-slate-500">{{ t.secret }}</p>
        <div class="grid grid-cols-2 gap-4">
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
        <button class="primary" :disabled="busy">{{ t.save }}</button>
      </form>
      <p v-if="message" class="mt-4 text-green-700" role="status">
        {{ message }}
      </p>
      <p v-if="error" role="alert">{{ error }}</p>
    </div>
    <div class="space-y-4">
      <article v-for="model in models" :key="model.id" class="panel">
        <h3 class="font-semibold">{{ model.name }}</h3>
        <p
          v-if="model.baseUrl.startsWith('mock:')"
          class="my-2 text-xs font-semibold text-amber-700"
        >
          {{
            lang === "zh"
              ? "模拟生成 · 不调用外部 LLM"
              : "SIMULATED · no external LLM"
          }}
        </p>
        <p class="my-2 break-all font-mono text-xs">ID: {{ model.id }}</p>
        <p class="break-all text-sm text-slate-500">
          {{ model.modelName }} · {{ model.baseUrl }}
        </p>
        <p class="my-3 text-xs">
          {{ model.keyConfigured ? t.configured : t.none }}
        </p>
        <button class="secondary" :disabled="busy" @click="test(model.id)">
          {{ t.test }}
        </button>
      </article>
    </div>
  </section>
</template>

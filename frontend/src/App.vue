<script setup lang="ts">
import { ref, onMounted } from "vue";
import Workspace from "./components/Workspace.vue";
import type { Lang } from "./types";
const lang = ref<Lang>("zh"),
  authenticated = ref(false),
  loading = ref(true),
  busy = ref(false),
  username = ref(""),
  password = ref(""),
  error = ref("");
const text = {
  zh: {
    login: "登录",
    logout: "注销",
    username: "用户名",
    password: "密码",
    subtitle: "基于图结构的智能体推演平台",
    invalid: "用户名或密码错误",
    network: "服务暂不可用，请重试",
    loading: "加载中…",
  },
  en: {
    login: "Sign in",
    logout: "Sign out",
    username: "Username",
    password: "Password",
    subtitle: "Graph-based agent simulation platform",
    invalid: "Invalid username or password",
    network: "Service unavailable. Try again.",
    loading: "Loading…",
  },
};
onMounted(async () => {
  try {
    authenticated.value = (await fetch("/api/auth/me")).ok;
  } catch {
    error.value = text[lang.value].network;
  } finally {
    loading.value = false;
  }
});
function toggle() {
  lang.value = lang.value === "zh" ? "en" : "zh";
  document.documentElement.lang = lang.value === "zh" ? "zh-CN" : "en";
  error.value = "";
}
async function login() {
  busy.value = true;
  error.value = "";
  try {
    const r = await fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username: username.value,
        password: password.value,
      }),
    });
    if (r.ok) {
      authenticated.value = true;
      password.value = "";
    } else
      error.value =
        r.status === 401 ? text[lang.value].invalid : text[lang.value].network;
  } catch {
    error.value = text[lang.value].network;
  } finally {
    busy.value = false;
  }
}
async function logout() {
  busy.value = true;
  try {
    const r = await fetch("/api/auth/logout", { method: "POST" });
    if (r.ok) {
      authenticated.value = false;
      password.value = "";
    } else error.value = text[lang.value].network;
  } catch {
    error.value = text[lang.value].network;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <header
    class="flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 bg-white px-6 py-4"
  >
    <div class="flex items-center gap-3">
      <span
        class="rounded-lg bg-blue-600 px-3 py-2 font-bold text-white"
        aria-hidden="true"
        >G</span
      >
      <h1 class="font-semibold">Graph Agent Simulation Studio</h1>
    </div>
    <div class="flex items-center gap-5">
      <button @click="toggle">{{ lang === "zh" ? "English" : "中文" }}</button
      ><button v-if="authenticated" :disabled="busy" @click="logout">
        {{ text[lang].logout }}
      </button>
    </div>
  </header>
  <main>
    <p v-if="loading" class="mt-24 text-center" role="status">
      {{ text[lang].loading }}
    </p>
    <section v-else-if="!authenticated" class="panel mx-auto mt-24 w-[calc(100%-2rem)] max-w-md">
      <p class="text-xs tracking-widest text-blue-600">GASS</p>
      <h2 class="mt-2 text-2xl font-semibold">{{ text[lang].login }}</h2>
      <p class="mb-8 mt-2 text-sm text-slate-500">{{ text[lang].subtitle }}</p>
      <form class="grid gap-5" @submit.prevent="login">
        <label
          >{{ text[lang].username
          }}<input v-model="username" autocomplete="username" required /></label
        ><label
          >{{ text[lang].password
          }}<input
            v-model="password"
            type="password"
            autocomplete="current-password"
            required
        /></label>
        <p v-if="error" role="alert">{{ error }}</p>
        <button class="primary" :disabled="busy">{{ text[lang].login }}</button>
      </form>
    </section>
    <Workspace v-else :lang="lang" />
    <p v-if="authenticated && error" role="alert">{{ error }}</p>
  </main>
</template>

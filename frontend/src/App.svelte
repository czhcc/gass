<script lang="ts">
 import { onMount } from 'svelte';
 let lang=$state<'zh'|'en'>('zh');
 const messages={zh:{username:'用户名',password:'密码',login:'登录',logout:'注销',error:'用户名或密码错误',network:'服务暂不可用，请重试',loading:'加载中…',subtitle:'基于图结构的智能体推演平台'},en:{username:'Username',password:'Password',login:'Sign in',logout:'Sign out',error:'Invalid username or password',network:'Service unavailable. Try again.',loading:'Loading…',subtitle:'Graph-based agent simulation platform'}};
 let t=$derived(messages[lang]);let username=$state('');let password=$state('');let authenticated=$state(false);let loading=$state(true);let busy=$state(false);let error=$state('');
 function language(){lang=lang==='zh'?'en':'zh';document.documentElement.lang=lang==='zh'?'zh-CN':'en';error='';}
 onMount(async()=>{try {authenticated=(await fetch('/api/auth/me')).ok;}catch{error=t.network;}finally{loading=false;}});
 async function login(e:SubmitEvent){e.preventDefault();busy=true;error='';try{const res=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username,password})});if(res.ok){authenticated=true;password='';}else{error=res.status===401?t.error:t.network;}}catch{error=t.network;}finally{busy=false;}}
 async function logout(){busy=true;error='';try{const res=await fetch('/api/auth/logout',{method:'POST'});if(res.ok){authenticated=false;password='';}else error=t.network;}catch{error=t.network;}finally{busy=false;}}
</script>
<svelte:head><meta name="description" content="Graph Agent Simulation Studio" /></svelte:head>
<header class="flex items-center justify-between border-b border-slate-200 bg-white px-6 py-5 gap-4">
 <div class="flex items-center gap-3"><span aria-hidden="true" class="rounded-lg bg-blue-600 px-3 py-2 text-white font-bold">G</span><h1 class="text-sm sm:text-lg font-semibold">Graph Agent Simulation Studio</h1></div>
 <div class="flex gap-5 items-center"><button class="text-sm text-slate-600 hover:text-blue-600" onclick={language}>{lang==='zh'?'English':'中文'}</button>{#if authenticated}<button class="rounded-md border border-slate-300 px-4 py-2 text-sm" disabled={busy} onclick={logout}>{t.logout}</button>{/if}</div>
</header>
<main class="min-h-[80vh] px-5">
 {#if loading}<p class="text-center mt-24" role="status">{t.loading}</p>
 {:else if !authenticated}<section class="mx-auto mt-24 max-w-sm rounded-2xl border border-slate-200 bg-white p-8 shadow-sm"><p class="mb-2 text-xs font-semibold tracking-widest text-blue-600">GASS</p><h2 class="text-2xl font-semibold mb-2">{t.login}</h2><p class="text-sm text-slate-500 mb-8">{t.subtitle}</p>
 <form onsubmit={login} class="flex flex-col gap-5"><label class="text-sm font-medium">{t.username}<input class="mt-2 block w-full rounded-lg border border-slate-300 px-3 py-2.5" autocomplete="username" bind:value={username} required /></label><label class="text-sm font-medium">{t.password}<input class="mt-2 block w-full rounded-lg border border-slate-300 px-3 py-2.5" type="password" autocomplete="current-password" bind:value={password} required /></label>{#if error}<p role="alert" class="text-sm text-red-600">{error}</p>{/if}<button disabled={busy} class="rounded-lg bg-blue-600 py-3 font-medium text-white hover:bg-blue-700">{t.login}</button></form></section>
 {:else if error}<p role="alert" class="mt-6 text-red-600">{error}</p>{/if}
</main>

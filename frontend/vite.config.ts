import { defineConfig } from 'vitest/config';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import tailwindcss from '@tailwindcss/vite';
export default defineConfig({plugins:[svelte(),tailwindcss()],resolve:{conditions:['browser']},server:{proxy:{'/api':'http://localhost:8080'}},test:{environment:'jsdom',include:['src/**/*.test.ts']}});

/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: process.env.VITE_BASE_URL || '/',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    chunkSizeWarningLimit: 3000,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // `api/client.ts` DEV modunda boş bir base URL'de fail-fast eder ve Vitest de DEV modunda
    // koşar; bu değer olmadan client'ı import eden her test o guard'a çarpar. Üretim kodunu
    // "test miyim" diye sorgulatmak yerine test ortamını gerçeğe benzetmek tercih edildi —
    // aksi hâlde guard üretimde bozulur ve testlerde fark edilmez.
    // Guard'ın kendisi `src/api/__tests__/client-config.test.ts` içinde, env'i tekrar boşaltarak
    // ayrıca test edilir; yoksa buradaki değer korumayı sessizce ölü bırakırdı.
    env: {
      VITE_API_BASE_URL: 'http://localhost:8080',
    },
  },
});

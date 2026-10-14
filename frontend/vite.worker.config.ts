import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
export default defineConfig({
  publicDir: false,
  build: {
    emptyOutDir: false,
    lib: { entry: fileURLToPath(new URL('./workers/firebase-messaging-sw.ts', import.meta.url)), name: 'NotificationWorker', formats: ['iife'], fileName: () => 'firebase-messaging-sw.js' },
  },
})

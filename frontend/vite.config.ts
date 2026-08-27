import react from '@vitejs/plugin-react'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, process.cwd(), '')
  if (mode === 'production' && !environment.VITE_API_BASE_URL) {
    throw new Error('VITE_API_BASE_URL must be configured for production builds.')
  }

  return {
    plugins: [react()],
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
    },
  }
})

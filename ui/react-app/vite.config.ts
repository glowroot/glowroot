import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: '/modern/',
  experimental: {
    renderBuiltUrl(_filename, { hostType }) {
      if (hostType === 'css') {
        return { relative: true }
      }
      return { relative: false }
    },
  },
  build: {
    outDir: '../target/generated-resources/react-dist/org/glowroot/ui/react-dist',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/backend': 'http://localhost:4000',
      '/export': 'http://localhost:4000',
    },
  },
})

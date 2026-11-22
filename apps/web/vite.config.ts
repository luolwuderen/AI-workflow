import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    target: 'es2022',
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (!id.includes('node_modules')) return
          if (id.includes('@vue-flow')) return 'vendor-flow'
          if (id.includes('lucide-vue-next')) return 'vendor-icons'
          if (id.includes('/vue/') || id.includes('/@vue/') || id.includes('/pinia/')) return 'vendor-vue'
        },
      },
    },
  },
})

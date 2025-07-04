import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import legacy from '@vitejs/plugin-legacy'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    legacy({
      targets: ['defaults', 'not IE 11'],
      polyfills: ['es.promise', 'es.array.includes', 'es.string.includes', 'es.symbol', 'es.object.assign']
    })
  ],
  build: {
    target: ['es2015', 'chrome63', 'firefox67', 'safari11.1']
  }
})

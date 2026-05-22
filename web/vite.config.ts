import { fileURLToPath } from 'node:url';
import path from 'node:path';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// The React Compiler is intentionally OFF for MVP per
// architecture_decisions.frontend_build. Do not add the optional Babel
// compiler plugin or its config object to the react() call below.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    host: true,
    strictPort: true,
    proxy: {
      // Dev-only: forward backend calls to the Spring Boot API on :8080 so we
      // avoid CORS during local dev. ws:false because architecture_decisions.realtime
      // is HTTP polling only — no WebSocket, no SSE.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: false,
      },
    },
  },
  preview: {
    port: 4173,
    strictPort: true,
  },
});

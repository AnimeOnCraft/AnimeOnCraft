import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({ plugins: [react()], clearScreen: false,
  optimizeDeps: { include: ['react', 'react-dom/client', 'react/jsx-runtime', 'react/jsx-dev-runtime', '@tauri-apps/api/core'] },
  server: { host: '127.0.0.1', port: 1420, strictPort: true, watch: { ignored: ['**/src-tauri/**'] }, proxy: {
    '/api/v1/clans': 'http://127.0.0.1:3000', '/api/v1/news': 'http://127.0.0.1:3000',
    '/api/v1/health/ready': 'http://127.0.0.1:3000', '/api/v1/launcher/compatibility': 'http://127.0.0.1:3000', '/api/v1/world': 'http://127.0.0.1:3000'
  } }, envPrefix: ['VITE_'] });

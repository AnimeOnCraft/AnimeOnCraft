import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({ plugins: [react()], base: '/admin/', server: { host: '127.0.0.1', proxy: { '/api/v1/clans': 'http://127.0.0.1:3000' } } });

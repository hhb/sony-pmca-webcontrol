import basicSsl from '@vitejs/plugin-basic-ssl';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import { viteSingleFile } from 'vite-plugin-singlefile';

const cameraTarget = process.env.VITE_CAMERA_URL || 'http://192.168.0.250:8080';
const backendTarget = process.env.VITE_BACKEND_URL || 'http://127.0.0.1:8000';

export default defineConfig(({ command }) => ({
  plugins: [
    react(),
    ...(command === 'serve' ? [basicSsl()] : []),
    viteSingleFile(),
  ],
  server: {
    host: true,
    proxy: {
      '/api': {
        target: cameraTarget,
        changeOrigin: true,
      },
      '/local-api': {
        target: backendTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    target: 'es2020',
    outDir: 'dist',
    assetsInlineLimit: 100_000_000,
    cssCodeSplit: false,
  },
}));

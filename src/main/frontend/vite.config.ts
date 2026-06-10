import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Built output (dist/) is copied into Spring Boot static resources by Maven.
// During `npm run dev`, proxy the backend API to the running Spring Boot app.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/internal': 'http://localhost:8080',
    },
  },
});

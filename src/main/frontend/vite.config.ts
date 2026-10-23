import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Built output (dist/) is copied into Spring Boot static resources by Maven.
// During `npm run dev`, proxy the backend API to the running Spring Boot app.
export default defineConfig({
  plugins: [react()],
  define: {
    // Stamped into the bundle so the header can show when THIS build was made (see src/version.ts).
    __BUILD_TIME__: JSON.stringify(new Date().toISOString()),
  },
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

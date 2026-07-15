import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev: proxy /api to the backend so the SPA uses same-origin relative URLs (like prod behind edge nginx).
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
});

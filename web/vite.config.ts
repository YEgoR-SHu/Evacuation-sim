import { defineConfig } from 'vite';
import { resolve } from 'node:path';
import { fileURLToPath, URL } from 'node:url';
const root = fileURLToPath(new URL('.', import.meta.url));
export default defineConfig({
    publicDir: false,
    build: {
        target: 'es2022', outDir: 'dist', emptyOutDir: true,
        rollupOptions: {
            input: Object.fromEntries(['index', 'login', 'buildings', 'building', 'scenario', 'viewer'].map(name => [name, resolve(root, `${name}.html`)])),
        },
    },
    server: { port: 5173, proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } } },
});

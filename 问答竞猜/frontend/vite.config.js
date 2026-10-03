import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import {fileURLToPath} from 'node:url'
export default defineConfig({base:'./',plugins:[vue()],build:{rollupOptions:{input:{demo:fileURLToPath(new URL('./index.html',import.meta.url)),server:fileURLToPath(new URL('./server.html',import.meta.url))}}},server:{host:'127.0.0.1',proxy:{'/api/quiz':{target:'http://127.0.0.1:8081',changeOrigin:false}}}})

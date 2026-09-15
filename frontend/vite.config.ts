import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// :web이 dist를 정적 리소스로 서빙한다(같은 오리진) — 별도 base 경로나 CORS 설정이 필요 없다.
export default defineConfig({
  plugins: [react()],
})

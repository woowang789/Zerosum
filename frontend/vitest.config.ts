import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config.ts'

// vitest.config.ts가 있으면 vitest는 원래 이 파일만 읽고 vite.config.ts를 보지 않는다 — plugins나
// alias가 프로덕션 설정에만 늘어나면 테스트가 그걸 모른 채 다른 모듈 해석으로 조용히 돈다. mergeConfig로
// vite.config.ts를 베이스로 명시 상속하고, test 옵션만 이 파일에서 얹는다. 상속 방향이
// vite → test이므로 테스트 설정이 프로덕션 빌드 쪽으로 새어 나가지는 않는다(vite.config.ts는 그대로다).
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      // JUnit XML을 build/test-results/test/ 아래로 쓴다. CI의 "테스트가 실제로 돌았는지" 가드가
      // **/build/test-results/test/*.xml 을 재귀 탐색해 합산하므로, Gradle 모듈과 같은 자리에 두면 CI
      // 파일을 고치지 않고도 이 숫자가 그 합계에 들어간다. 다만 가드는 total > 0만 보고 Java 쪽이 이미
      // 수백 건을 채우므로, 프론트가 0건이어도 가드는 통과한다 — 프론트를 실제로 지키는 것은 이 값이
      // 아니라 testFrontend(Exec) 태스크 자신의 종료 코드다.
      reporters: ['default', ['junit', { suiteName: 'frontend' }]],
      outputFile: { junit: 'build/test-results/test/vitest-junit.xml' },
    },
  }),
)

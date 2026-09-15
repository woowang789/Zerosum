plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // 코어의 진입점(ProposalGateway·ReconciliationService 등)만 쓴다. 웹 의존성은 이 모듈에 갇힌다 —
    // :core의 테스트가 서블릿 컨테이너 없이 계속 돌아야 하기 때문이다 (:mcp-server가 Jackson을 가두는 것과 같은 이유).
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // IssueRepository가 app_rw(primary) JdbcClient로 v_open_issue 등을 직접 읽는다 — :core의 implementation
    // 의존성은 컴파일 클래스패스로 전이되지 않으므로 이 모듈도 명시적으로 선언해야 한다.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4에서 @AutoConfigureMockMvc 가 이 아티팩트로 분리됐다 (3.x의 spring-boot-test-autoconfigure 아님).
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // db/ 가 저장소 루트에 있어서다 (:core와 같은 이유).
    workingDir = rootProject.projectDir
}

// 프론트엔드 빌드 — frontend/(저장소 루트, :web과 형제)를 npm으로 빌드해 이 모듈의 정적 리소스로
// 넣는다. 같은 오리진에서 서빙되므로 CORS가 필요 없다. 이 태스크들은 :web에만 있다 — npm이 없는
// 환경에서도 :core·:mcp-server 테스트는 그대로 돌아야 하기 때문이다(둘 다 이 모듈에 의존하지 않는다).
val frontendDir = rootProject.file("frontend")

val npmInstall = tasks.register<Exec>("npmInstall") {
    workingDir = frontendDir
    inputs.file(frontendDir.resolve("package.json"))
    inputs.file(frontendDir.resolve("package-lock.json"))
    outputs.dir(frontendDir.resolve("node_modules"))
    commandLine("npm", "ci")
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
    dependsOn(npmInstall)
    workingDir = frontendDir
    inputs.dir(frontendDir.resolve("src"))
    inputs.file(frontendDir.resolve("index.html"))
    inputs.file(frontendDir.resolve("vite.config.ts"))
    inputs.file(frontendDir.resolve("tsconfig.json"))
    outputs.dir(frontendDir.resolve("dist"))
    commandLine("npm", "run", "build")
}

// 프론트엔드 산출물을 정적 리소스에 합친다. 별도 Copy 태스크로 빼서 processResources의 선행으로
// 걸면 순서가 거꾸로다 — 프론트엔드를 복사한 뒤 processResources가 src/main/resources 쪽 파일로
// 덮어쓴다(실측: Vite가 만든 index.html이 사라졌다). 한 번의 복사로 합친다.
tasks.named<ProcessResources>("processResources") {
    dependsOn(buildFrontend)
    from(frontendDir.resolve("dist")) {
        into("static")
    }
}

// 프론트엔드 테스트 — vitest가 JUnit XML을 frontend/build/test-results/test/ 아래로 쓴다. Gradle 모듈과
// 같은 자리이므로 CI의 "테스트가 실제로 돌았는지" 가드(**/build/test-results/test/*.xml 재귀 탐색)가 그
// 숫자를 함께 합산하기는 한다. 다만 그 가드는 total > 0만 보고 Java 쪽이 이미 수백 건을 채우므로,
// 프론트가 0건이어도 가드는 그대로 통과한다 — 프론트 테스트가 실제로 돌았다는 보장은 이 태스크(testFrontend)
// 자신의 종료 코드가 0이 아니면 :web:test가 실패한다는 것뿐이다.
//
// :web:test에 걸어 두는 이유는, 이 테스트들이 지키는 것이 :web이 서빙하는 화면의 동작이기 때문이다.
// buildFrontend와 마찬가지로 :web에만 있다 — npm이 없는 환경에서도 :core·:mcp-server는 그대로 돌아야 한다.
val testFrontend = tasks.register<Exec>("testFrontend") {
    dependsOn(npmInstall)
    workingDir = frontendDir
    inputs.dir(frontendDir.resolve("src"))
    inputs.file(frontendDir.resolve("vitest.config.ts"))
    inputs.file(frontendDir.resolve("tsconfig.json"))
    // outputs.dir(...)로 산출물을 선언하면 cleanTest 뒤에도(:web:cleanTest :web:test 연속 실행 등) 이
    // 태스크가 UP-TO-DATE로 건너뛰어질 수 있다 — cleanTest는 Gradle test 태스크 산출물만 지우고 이
    // Exec 태스크의 outputs.dir은 그대로 남기 때문이다. 그러면 frontend/build/test-results/의 낡은
    // XML이 남아 위 가드에까지 집계되어 "아무것도 안 돌았는데 초록불"이 된다. 프론트 테스트는 전부
    // 합쳐도 2초 안팎이라 매번 다시 돌려도 싸다 — 증분 빌드를 포기하고 항상 실행되게 한다.
    outputs.upToDateWhen { false }
    commandLine("npm", "run", "test")
}

tasks.named<Test>("test") {
    dependsOn(testFrontend)
}

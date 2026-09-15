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

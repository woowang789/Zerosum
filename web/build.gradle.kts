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

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.zerosum"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // 코어의 서비스(AiQueryService 등)에 위임만 한다 — 로직은 여기 없다
    implementation(project(":core"))
    // 기본 스타터는 STDIO 전송이다. -webmvc/-webflux 변형은 톰캣을 끌어들여 테스트 컨텍스트 종류를 바꾸므로 쓰지 않는다
    implementation("org.springframework.ai:spring-ai-starter-mcp-server:1.1.0")
    // spring-ai 1.1.0의 McpServerAutoConfiguration#mcpSyncServer가 (웹 스타터 없이도) 무조건
    // StandardServletEnvironment를 참조해, 이 라이브러리 없이는 NoClassDefFoundError로 부팅이 실패한다
    // (실측). 톰캣·spring-webmvc를 끌어들이는 -webmvc 스타터와 달리 spring-web은 서블릿 컨테이너를
    // 붙이지 않는 순수 라이브러리라 STDIO 전송·web-application-type=none에는 영향이 없다.
    implementation("org.springframework:spring-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

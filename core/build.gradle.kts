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
    // 쓰기 경로: JdbcClient로 SQL을 드러내서 작성한다 (docs/04-write-path.md 구현 메모)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // 마스터 데이터(warehouse/location/sku/lot) 읽기 전용 조회에만 사용한다
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 모듈 디렉터리(core/)가 아니라 프로젝트 루트를 작업 디렉터리로 맞춘다.
    // application.yml의 "filesystem:db/migration", AbstractIntegrationTest의 "db/00-roles.sql"이
    // 전부 루트 기준 상대 경로이기 때문이다 (db/는 여러 모듈이 공유하므로 core/가 아니라 루트에 둔다).
    workingDir = rootProject.projectDir
    // InventorySequencePropertyTest의 시드 재현("-Dzerosum.property.seed=<n>")용. Gradle은 커맨드라인
    // -D를 테스트를 포크한 JVM에 기본 전달하지 않으므로, 값이 있을 때만 그대로 넘겨준다.
    System.getProperty("zerosum.property.seed")?.let { systemProperty("zerosum.property.seed", it) }
}

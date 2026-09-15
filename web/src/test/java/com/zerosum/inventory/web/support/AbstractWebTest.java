package com.zerosum.inventory.web.support;

import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 웹 계층 테스트의 공통 바탕. :core의 AbstractIntegrationTest와 같은 방식으로 PostgreSQL 16 컨테이너를
 * 한 번 띄운다 — 두 모듈의 테스트 소스는 공유되지 않으므로 필요한 부분만 다시 세운다.
 *
 * <p>운영 설정(application.yml)은 Flyway를 꺼 둔다(마이그레이션은 배포 경로의 일이다). 테스트에서는
 * 스키마가 있어야 하므로 여기서만 켠다. {@code db/} 경로는 Test 태스크의 workingDir이 저장소 루트라
 * 그대로 해석된다(web/build.gradle.kts).
 */
@SpringBootTest
public abstract class AbstractWebTest {

    protected static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                .withDatabaseName("zerosum")
                .withUsername("postgres")
                .withPassword("postgres")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(Path.of("db/00-roles.sql")),
                        "/docker-entrypoint-initdb.d/00-roles.sql");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_rw");
        registry.add("spring.datasource.password", () -> "app_rw");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "migrator");
        registry.add("spring.flyway.password", () -> "migrator");
        registry.add("spring.flyway.locations", () -> "filesystem:db/migration");
        registry.add("zerosum.ai.read.url", POSTGRES::getJdbcUrl);
        registry.add("zerosum.ai.proposer.url", POSTGRES::getJdbcUrl);
    }
}

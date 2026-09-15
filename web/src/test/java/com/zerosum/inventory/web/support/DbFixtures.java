package com.zerosum.inventory.web.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 제안·이슈 API 테스트가 필요로 하는 마스터 데이터(창고·로케이션·SKU·로트) 시딩. AbstractWebTest는
 * 고치지 말라는 지시가 있어(WebSecurityTest는 시딩 없이도 통과한다) 여기 별도 도우미로 둔다 —
 * :core의 AbstractIntegrationTest#resetDatabase와 같은 절차(TRUNCATE 후 db/03-seed.sql 재적재)지만
 * 테스트 소스는 모듈 간에 공유되지 않으므로 다시 짠다.
 *
 * <p>app_rw는 warehouse·location·sku·lot에 SELECT만 있고 INSERT는 app_admin 전용이다(V2 GRANT) —
 * 그래서 시드는 app_admin 커넥션으로, TRUNCATE는 migrator 커넥션으로 실행한다.
 */
public final class DbFixtures {

    private DbFixtures() {
    }

    public static void resetDatabase(PostgreSQLContainer<?> postgres) throws SQLException {
        try (Connection migrator = DriverManager.getConnection(postgres.getJdbcUrl(), "migrator", "migrator");
                Statement st = migrator.createStatement()) {
            st.execute("""
                    TRUNCATE TABLE warehouse, location, sku, lot,
                                    stock_balance, idempotency_record, inventory_txn, inventory_ledger_entry,
                                    allocation, outbox_event, count_session, count_result,
                                    action_proposal, inventory_issue
                    RESTART IDENTITY CASCADE
                    """);
        }
        try (Connection appAdmin = DriverManager.getConnection(postgres.getJdbcUrl(), "app_admin", "app_admin");
                Statement st = appAdmin.createStatement()) {
            String seedSql;
            try {
                seedSql = Files.readString(Path.of("db/03-seed.sql"));
            } catch (Exception e) {
                throw new IllegalStateException("db/03-seed.sql을 읽을 수 없다 (작업 디렉터리 확인 필요)", e);
            }
            st.execute(seedSql);
        }
    }
}

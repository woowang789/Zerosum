package com.zerosum.inventory.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.domain.Posted;
import com.zerosum.inventory.domain.PostingOutcome;
import com.zerosum.inventory.posting.PostingGateway;
import com.zerosum.inventory.posting.PostingLineInput;
import com.zerosum.inventory.posting.PostingRequest;
import com.zerosum.inventory.posting.Preconditions;
import com.zerosum.inventory.repository.ReconciliationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 모든 통합 테스트의 베이스. PostgreSQL 16 컨테이너 하나를 JVM 전체에서 공유한다 (싱글톤 컨테이너 패턴 —
 * {@code @Testcontainers}/{@code @Container}를 쓰지 않고 정적 초기화 블록에서 한 번만 start()한다.
 * 여러 테스트 클래스가 이 베이스를 상속하며 @SpringBootTest 컨텍스트를 공유하므로 Flyway도 한 번만 돈다).
 *
 * <p>롤 생성(db/00-roles.sql)은 postgres 공식 이미지의 docker-entrypoint-initdb.d 메커니즘으로
 * 컨테이너 부트스트랩 시 슈퍼유저로 실행한다 — 과제 지시의 "컨테이너 superuser로 실행됨"과 동일한 효과이며,
 * 파일을 복사해 재사용하므로 db/00-roles.sql 자체를 고치거나 테스트 리소스로 중복 보관하지 않는다.
 * 그 뒤 Flyway가 migrator로 V1·V2를 적용하고, 각 테스트 전에는 스키마 14개 테이블을 전부 TRUNCATE한 뒤
 * db/03-seed.sql을 app_admin으로 다시 적재해 항상 같은 마스터 데이터에서 시작한다.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

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
    static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_rw");
        registry.add("spring.datasource.password", () -> "app_rw");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "migrator");
        registry.add("spring.flyway.password", () -> "migrator");
        // 동시성 테스트가 10개 안팎의 커넥션을 동시에 물고 대기하므로 기본 풀(10)보다 여유를 둔다
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
        // 스케줄러가 공유 컨텍스트에서 배경으로 돌면 테스트가 기대하는 published_at·inventory_issue
        // 상태와 우연히 경합할 수 있다 (3단계 작업 지시: "테스트에서는 스케줄러를 끌 수 있게 해라").
        // 관련 테스트는 OutboxRelayService#relayOnce()·ReconciliationService#runOnce()를 직접 호출한다.
        registry.add("zerosum.outbox.relay.enabled", () -> "false");
        registry.add("zerosum.reconciliation.enabled", () -> "false");
        // AI 전용 커넥션(ai_ro·ai_proposer)도 같은 컨테이너를 본다 (롤은 위 init 스크립트 db/00-roles.sql이 만든다).
        registry.add("zerosum.ai.read.url", POSTGRES::getJdbcUrl);
        registry.add("zerosum.ai.read.username", () -> "ai_ro");
        registry.add("zerosum.ai.read.password", () -> "ai_ro");
        registry.add("zerosum.ai.proposer.url", POSTGRES::getJdbcUrl);
        registry.add("zerosum.ai.proposer.username", () -> "ai_proposer");
        registry.add("zerosum.ai.proposer.password", () -> "ai_proposer");
    }

    @Autowired
    protected JdbcClient jdbcClient;

    @Autowired
    protected PostingGateway postingGateway;

    @Autowired
    protected ReconciliationRepository reconciliationRepository;

    // 매 테스트 전에 스키마 14개 테이블을 전부 비우고(TRUNCATE는 FK 제약이 있으면 참조하는 테이블까지
    // 같은 문장에 있어야 하므로 마스터까지 포함한다) db/03-seed.sql로 마스터 데이터를 다시 채운다.
    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection migrator = migratorConnection(); Statement st = migrator.createStatement()) {
            st.execute("""
                    TRUNCATE TABLE warehouse, location, sku, lot,
                                    stock_balance, idempotency_record, inventory_txn, inventory_ledger_entry,
                                    allocation, outbox_event, count_session, count_result,
                                    action_proposal, inventory_issue
                    RESTART IDENTITY CASCADE
                    """);
        }
        try (Connection appAdmin = appAdminConnection(); Statement st = appAdmin.createStatement()) {
            String seedSql;
            try {
                seedSql = Files.readString(Path.of("db/03-seed.sql"));
            } catch (Exception e) {
                throw new IllegalStateException("db/03-seed.sql을 읽을 수 없다 (작업 디렉터리 확인 필요)", e);
            }
            st.execute(seedSql);
        }
    }

    protected static Connection migratorConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "migrator", "migrator");
    }

    protected static Connection appAdminConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_admin", "app_admin");
    }

    // ── 커맨드 구성 헬퍼 (db/03-seed.sql의 마스터 코드를 그대로 쓴다) ─────────────────

    protected static PostingLineInput line(String warehouseCode, String locationCode, String skuCode, String lotNo,
            int qty) {
        return new PostingLineInput(warehouseCode, locationCode, skuCode, lotNo, qty);
    }

    protected static PostingRequest request(String idemKey, String txnType, String reasonCode, Long reversesTxnId,
            PostingLineInput... lines) {
        return new PostingRequest(idemKey, txnType, "USER", "user:test", List.of(lines),
                "TEST", idemKey, reasonCode, reversesTxnId, Instant.now());
    }

    protected long postAndExpectSuccess(PostingRequest request) {
        PostingOutcome outcome = postingGateway.post(request, Preconditions.none());
        assertThat(outcome).isInstanceOf(Posted.class);
        return ((Posted) outcome).txnId();
    }

    // ── 조회 헬퍼 (db/04-harness.sql의 tst_qty/tst_avail에 대응) ─────────────────────

    protected int onHandQty(String warehouseCode, String locationCode, String skuCode, String lotNo) {
        return jdbcClient.sql("""
                SELECT b.on_hand_qty FROM stock_balance b
                JOIN location l ON l.id = b.location_id JOIN warehouse w ON w.id = l.warehouse_id
                JOIN sku s ON s.id = b.sku_id JOIN lot lo ON lo.id = b.lot_id
                WHERE w.code = :wh AND l.code = :loc AND s.code = :sku AND lo.lot_no = :lot
                """)
                .param("wh", warehouseCode)
                .param("loc", locationCode)
                .param("sku", skuCode)
                .param("lot", lotNo)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    protected int balanceRowCount(String warehouseCode, String locationCode) {
        return jdbcClient.sql("""
                SELECT count(*) FROM stock_balance b
                JOIN location l ON l.id = b.location_id JOIN warehouse w ON w.id = l.warehouse_id
                WHERE w.code = :wh AND l.code = :loc
                """)
                .param("wh", warehouseCode)
                .param("loc", locationCode)
                .query(Integer.class)
                .single();
    }

    /** 로케이션의 실사 표시. count_session_id는 실사 중이 아니면 널이다. */
    protected Long countSessionIdOf(String warehouseCode, String locationCode) {
        record Row(Long countSessionId) {
        }
        return jdbcClient.sql("""
                SELECT count_session_id FROM location l JOIN warehouse w ON w.id = l.warehouse_id
                WHERE w.code = :wh AND l.code = :loc
                """)
                .param("wh", warehouseCode)
                .param("loc", locationCode)
                .query((rs, rowNum) -> new Row((Long) rs.getObject("count_session_id")))
                .single()
                .countSessionId();
    }

    protected String countSessionStatus(long sessionId) {
        return jdbcClient.sql("SELECT status FROM count_session WHERE id = :id")
                .param("id", sessionId)
                .query(String.class)
                .single();
    }

    protected String txnType(long txnId) {
        return jdbcClient.sql("SELECT txn_type FROM inventory_txn WHERE id = :id")
                .param("id", txnId)
                .query(String.class)
                .single();
    }

    /**
     * 아직 닫히지 않은 COUNT_VARIANCE. 'OPEN'만 세면 담당자가 인지한(ACKED) 이슈가 종결에서 빠져도
     * 0이 나와 통과한다 — 실제로 있었던 결함이라(CountResultRepository#resolveVarianceIssues)
     * "RESOLVED가 아닌 것"으로 센다.
     */
    protected int openCountVarianceIssueCount() {
        return jdbcClient
                .sql("SELECT count(*) FROM inventory_issue WHERE issue_type = 'COUNT_VARIANCE'"
                        + " AND status <> 'RESOLVED'")
                .query(Integer.class)
                .single();
    }

    /** 원장을 직접 INSERT하는 등 저수준 테스트에 필요한 id 묶음. */
    protected record Ids(long warehouseId, long locationId, long skuId, long lotId) {
    }

    protected Ids resolveIds(String warehouseCode, String locationCode, String skuCode, String lotNo) {
        return jdbcClient.sql("""
                SELECT w.id AS wh, l.id AS loc, s.id AS sku, lo.id AS lot
                FROM warehouse w, location l, sku s, lot lo
                WHERE w.code = :wh AND l.warehouse_id = w.id AND l.code = :loc
                  AND s.code = :sku AND lo.sku_id = s.id AND lo.lot_no = :lot
                """)
                .param("wh", warehouseCode)
                .param("loc", locationCode)
                .param("sku", skuCode)
                .param("lot", lotNo)
                .query((rs, rowNum) -> new Ids(rs.getLong("wh"), rs.getLong("loc"), rs.getLong("sku"), rs.getLong("lot")))
                .single();
    }

    // ── 정합 검증 배치 (docs/06-events-reconciliation.md ①~⑤) ────────────────────────

    /**
     * ①~⑤ 다섯 쿼리를 모두 실행해 전부 0건인지 단언한다. 실사는 잔액·원장·할당·로케이션 표시를 한꺼번에
     * 건드려 불변식이 깨질 지점이 가장 많으므로, 이번에 만드는 실사 테스트는 모두 끝에서 이 메서드를 호출한다.
     *
     * <p>쿼리 정본은 {@link ReconciliationRepository}에 있다 — 운영 배치(ReconciliationService)도
     * 같은 리포지토리를 호출하므로 테스트 오라클과 배치가 같은 쿼리를 쓴다 (3단계 작업 지시 1번).
     */
    protected void assertReconciliationClean() {
        List<ReconciliationRepository.CheckSummary> checks = reconciliationRepository.summarize();

        assertThat(checks)
                .as("정합 검증 배치 ①~⑤(docs/06-events-reconciliation.md)는 모두 0건이어야 한다: %s", checks)
                .allSatisfy(check -> assertThat(check.count()).as(check.label()).isZero());
    }
}

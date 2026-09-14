package com.zerosum.inventory.ai;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * AI 전용 커넥션(ai_ro·ai_proposer)의 DataSource/JdbcClient 빈. app_rw용 기본 DataSource(@Primary 없음,
 * 기존 spring.datasource.*)와는 별도의 풀로 둔다 — 권한 경계를 앱 코드의 규율이 아니라 DB 계정 자체가
 * 지키게 하려는 것이다(V4 마이그레이션의 GRANT). ai_ro는 조회 뷰만 읽고, ai_proposer는 제안 INSERT와
 * inventory_issue.ai_analysis UPDATE만 한다. 두 계정 모두 트랜잭션 매니저 없이 autocommit 단문만 쓴다.
 *
 * <p>네 빈 모두 {@code defaultCandidate = false}를 둔다 — app_rw용 기본 DataSource/JdbcClient가 여전히
 * 유일한 "기본 후보"로 남아야 JPA·JdbcTemplate 자동 구성의 {@code @ConditionalOnSingleCandidate}가 계속
 * 성립한다(@Primary를 붙이는 대신 이 방법으로 기존 자동 구성을 건드리지 않는다). 이 빈들은 아래처럼
 * {@code @Qualifier}로 이름을 지정해야만 주입되고, 타입만으로는 후보에 잡히지 않는다.
 */
@Configuration
public class AiDataSourceConfig {

    // ai_ro: v_ledger·v_open_issue·v_count_history 등 조회 뷰만 읽는 읽기 전용 계정.
    @Bean(defaultCandidate = false)
    DataSource aiReadDataSource(
            @Value("${zerosum.ai.read.url}") String url,
            @Value("${zerosum.ai.read.username}") String username,
            @Value("${zerosum.ai.read.password}") String password) {
        return hikariDataSource(url, username, password);
    }

    // 위 ai_ro DataSource 전용 JdbcClient. app_rw용 기본 JdbcClient와 섞이지 않도록 별도 빈으로 둔다.
    @Bean(defaultCandidate = false)
    JdbcClient aiReadJdbcClient(@Qualifier("aiReadDataSource") DataSource aiReadDataSource) {
        return JdbcClient.create(aiReadDataSource);
    }

    // ai_proposer: action_proposal INSERT와 inventory_issue.ai_analysis UPDATE만 할 수 있는 쓰기 계정.
    @Bean(defaultCandidate = false)
    DataSource aiProposerDataSource(
            @Value("${zerosum.ai.proposer.url}") String url,
            @Value("${zerosum.ai.proposer.username}") String username,
            @Value("${zerosum.ai.proposer.password}") String password) {
        return hikariDataSource(url, username, password);
    }

    // 위 ai_proposer DataSource 전용 JdbcClient.
    @Bean(defaultCandidate = false)
    JdbcClient aiProposerJdbcClient(@Qualifier("aiProposerDataSource") DataSource aiProposerDataSource) {
        return JdbcClient.create(aiProposerDataSource);
    }

    private static DataSource hikariDataSource(String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        return new HikariDataSource(config);
    }
}

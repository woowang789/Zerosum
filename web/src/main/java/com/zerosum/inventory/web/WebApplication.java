package com.zerosum.inventory.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 사람이 쓰는 웹 진입점. AI는 :mcp-server로, 사람은 이 모듈로 들어온다.
 *
 * <p>스캔 범위를 코어까지 넓힌다 — 이 모듈은 코어의 서비스(ProposalGateway·ReconciliationService 등)를
 * 그대로 주입받아 얇게 노출하기만 한다.
 */
@SpringBootApplication(scanBasePackages = "com.zerosum.inventory")
public class WebApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}

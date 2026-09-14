package com.zerosum.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// 아웃박스 릴레이·정합 검증 배치의 @Scheduled 주기 실행을 켠다 (docs/06-events-reconciliation.md)
@SpringBootApplication
@EnableScheduling
public class ZerosumApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZerosumApplication.class, args);
    }
}

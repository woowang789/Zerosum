package com.zerosum.inventory.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * MCP 서버 진입점. 코어(com.zerosum.inventory 전체)를 컴포넌트 스캔 대상으로 삼아 {@code AiQueryService} 등
 * 코어 빈을 그대로 재사용한다 — mcp-server 모듈에는 {@link InventoryTools} 말고 별도 서비스 빈을 두지 않는다.
 *
 * <p>스캔 범위에 코어의 {@code com.zerosum.inventory.ZerosumApplication}도 들어오지만, 그 클래스는
 * main()이 이 프로세스에서 호출되지 않는 한 평범한 @Configuration 빈 하나로만 등록될 뿐이라 이 클래스와
 * 충돌하지 않는다(클래스 이름·빈 이름 모두 겹치지 않는다).
 */
@SpringBootApplication(scanBasePackages = "com.zerosum.inventory")
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    // InventoryTools의 @Tool 메서드를 MCP 서버 자동 구성이 도구로 등록하도록 콜백 제공자로 노출한다.
    @Bean
    ToolCallbackProvider inventoryToolCallbacks(InventoryTools inventoryTools) {
        return MethodToolCallbackProvider.builder().toolObjects(inventoryTools).build();
    }
}

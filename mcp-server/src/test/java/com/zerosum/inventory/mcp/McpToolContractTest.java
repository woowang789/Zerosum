package com.zerosum.inventory.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

/**
 * {@link InventoryTools}를 리플렉션으로만 검사하는 순수 단위 테스트다 — Docker·DB·Spring 컨텍스트가 없어도 돈다.
 * 핵심은 {@link #toolMethodsDoNotExposeWarehouseParameter()}: 창고 코드가 도구 파라미터로 새어 나가면
 * (4단계에서 막은) 권한 누수가 다시 뚫리므로, 이 회귀를 코드 리뷰가 아니라 테스트로 고정해 둔다.
 */
class McpToolContractTest {

    private static final Set<String> EXPECTED_TOOL_NAMES = Set.of("get_available_stock", "get_ledger",
            "list_open_issues", "get_issue_context", "write_issue_analysis", "create_proposal");

    private List<Method> toolMethods() {
        return Arrays.stream(InventoryTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .toList();
    }

    @Test
    void toolMethodsDoNotExposeWarehouseParameter() {
        for (Method method : toolMethods()) {
            for (Parameter param : method.getParameters()) {
                assertThat(param.getName().toLowerCase())
                        .as("%s#%s의 파라미터 %s는 창고 코드를 노출하면 안 된다", InventoryTools.class.getSimpleName(),
                                method.getName(), param.getName())
                        .doesNotContain("warehouse");
            }
        }
    }

    @Test
    void allSixToolsArePresent() {
        List<Method> tools = toolMethods();
        assertThat(tools).hasSize(6);

        Set<String> actualNames = tools.stream()
                .map(m -> m.getAnnotation(Tool.class).name())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(actualNames).isEqualTo(EXPECTED_TOOL_NAMES);
    }

    @Test
    void toolsHaveKoreanDescriptions() {
        for (Method method : toolMethods()) {
            String description = method.getAnnotation(Tool.class).description();
            assertThat(description)
                    .as("%s#%s의 @Tool description", InventoryTools.class.getSimpleName(), method.getName())
                    .isNotBlank();
            assertThat(description.chars().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3))
                    .as("%s#%s의 @Tool description은 한국어여야 한다: %s", InventoryTools.class.getSimpleName(),
                            method.getName(), description)
                    .isTrue();
        }
    }
}

package com.zerosum.inventory.web.stock;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zerosum.inventory.web.support.AbstractWebTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 재고 현황 화면은 창고에 실제로 있는 재고를 전부 보여줘야 한다.
 *
 * <p>{@code v_available_stock}은 {@code is_sellable}(= location_type = 'STORAGE')만 통과시킨다.
 * 그 뷰의 목적은 AI가 보는 범위를 좁히는 것이지 사람이 보는 범위를 정하는 것이 아니다. 사람 화면이
 * 그대로 빌려 쓰면 RECEIVING에 쌓인 입고분(치워야 할 것), RETURN_HOLD의 반품(검수해야 할 것),
 * DAMAGED의 파손품이 화면에서 사라진다 — 작업자가 가장 먼저 봐야 할 재고들이다.
 */
@AutoConfigureMockMvc
class StockVisibilityTest extends AbstractWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void receivingStockIsVisible() throws Exception {
        seed("RCV-01", 40);

        mockMvc.perform(get("/api/stock").param("warehouse", "ICN01").param("sku", "SKU-200002")
                        .with(httpBasic("choi.dw", "zerosum")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.locationCode == 'RCV-01')]").exists());
    }

    @Test
    void returnHoldStockIsVisible() throws Exception {
        seed("RTN-01", 6);

        mockMvc.perform(get("/api/stock").param("warehouse", "ICN01").param("sku", "SKU-200002")
                        .with(httpBasic("choi.dw", "zerosum")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.locationCode == 'RTN-01')]").exists());
    }

    /** 잔액 행을 직접 심는다 — 이 테스트가 보려는 것은 조회 경로이지 포스팅 경로가 아니다. */
    private void seed(String locationCode, int qty) {
        jdbcClient.sql("""
                INSERT INTO stock_balance (warehouse_id, location_id, sku_id, lot_id, on_hand_qty, allocated_qty)
                SELECT w.id, l.id, s.id, lo.id, :qty, 0
                FROM warehouse w
                JOIN location l ON l.warehouse_id = w.id AND l.code = :loc
                JOIN sku s ON s.code = 'SKU-200002'
                JOIN lot lo ON lo.sku_id = s.id AND lo.lot_no = 'L20260910-B'
                WHERE w.code = 'ICN01'
                ON CONFLICT (location_id, sku_id, lot_id)
                DO UPDATE SET on_hand_qty = EXCLUDED.on_hand_qty
                """)
                .param("qty", qty)
                .param("loc", locationCode)
                .update();
    }
}

package com.zerosum.inventory.web.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zerosum.inventory.web.support.AbstractWebTest;
import com.zerosum.inventory.web.support.DbFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 포스팅 쓰기 엔드포인트(입고·출고·이동·조정). 멱등 키 서버 파생, 가상 로케이션 직접 지정 거절, 역할
 * (조정은 SUPERVISOR 전용), 창고 범위가 핵심이다.
 *
 * {@link com.zerosum.inventory.web.issue.IssueControllerTest}의 클래스 주석 참고(UserDetailsService가
 * 사용자당 인스턴스 하나를 재사용해, 같은 사용자의 두 번째 로그인이 항상 401이 된다).
 */
@AutoConfigureMockMvc
class PostingControllerTest extends AbstractWebTest {

    private static final String PASSWORD = "zerosum";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void seed() throws Exception {
        DbFixtures.resetDatabase(POSTGRES);
    }

    @Test
    void receiptCreatesBothLedgerLines() throws Exception {
        long txnId = receive("PO-1", 1, "ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 30, "park.jh");

        assertThat(ledgerLineCount(txnId)).isEqualTo(2);
        assertThat(ledgerQtySum(txnId)).isEqualTo(0);
    }

    @Test
    void clientCannotSpecifyVirtualLocation() throws Exception {
        mockMvc.perform(post("/api/receipts").with(httpBasic("park.jh", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(receiptJson("PO-2", 1, "ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VIRTUAL_LOCATION_NOT_ALLOWED"));
    }

    /**
     * 음수 수량은 거래의 방향을 통째로 뒤집는다 — 출고인데 물리 줄이 +50, V-CUSTOMER가 -50이 되어
     * txn_type=SHIPMENT·reason_code=NULL인 거래가 실재고를 늘린다. 원장과 잔액이 완벽히 일치하므로
     * 정합 검증 ①~⑤는 이것을 영원히 보지 못한다. 컨트롤러가 요청 형태로 먼저 막고, 코어의 가상
     * 로케이션 규칙이 한 겹 더 막는다 (core VirtualLocationRuleTest).
     */
    @Test
    void negativeShipmentQtyIsRejected() throws Exception {
        mockMvc.perform(post("/api/shipments").with(httpBasic("park.jh", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderLineRef":"ORD-NEG-1","shipmentSeq":1,"warehouseCode":"ICN01",
                                 "lines":[{"locationCode":"A-01-01-1","skuCode":"SKU-100001",
                                           "lotNo":"DEFAULT","qty":-50}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NON_POSITIVE_QTY"));

        // 거절은 컨트롤러에서 끝나므로 거래도, 그 거래가 태울 뻔한 멱등 키도 남지 않는다.
        assertThat(txnCountForIdemKey("shipment:ORD-NEG-1:1")).isZero();
    }

    @Test
    void negativeReceiptQtyIsRejected() throws Exception {
        receive("PO-NEG-1", 1, "ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 20, "park.jh");

        mockMvc.perform(post("/api/receipts").with(httpBasic("park.jh", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(receiptJson("PO-NEG-2", 1, "ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -20)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NON_POSITIVE_QTY"));

        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(20);
    }

    @Test
    void duplicateReceiptIsIdempotent() throws Exception {
        long first = receive("PO-3", 1, "ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 12, "park.jh");
        long second = receive("PO-3", 1, "ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 12, "park.jh");

        assertThat(second).isEqualTo(first);
        assertThat(txnCountForIdemKey("receipt:PO-3:1")).isEqualTo(1);
    }

    @Test
    void operatorCannotAdjust() throws Exception {
        mockMvc.perform(post("/api/adjustments").with(httpBasic("park.jh", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustmentJson("ADJ-1", "ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 5,
                                "DAMAGE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void supervisorCanAdjust() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/adjustments").with(httpBasic("choi.dw", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustmentJson("ADJ-2", "ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 5,
                                "DAMAGE")))
                .andExpect(status().isOk())
                .andReturn();

        long txnId = txnIdFrom(result);
        assertThat(ledgerLineCount(txnId)).isEqualTo(2);
        assertThat(ledgerQtySum(txnId)).isEqualTo(0);
    }

    @Test
    void otherWarehouseWriteIsForbidden() throws Exception {
        // park.jh는 ICN01만 볼 수 있다 — 물리 줄이 YIT01을 가리키는 입고 요청은 창고 검증에서 거절돼야 한다.
        mockMvc.perform(post("/api/receipts").with(httpBasic("park.jh", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(receiptJson("PO-4", 1, "YIT01", "A-01-01-1", "SKU-100001", "DEFAULT", 10)))
                .andExpect(status().isForbidden());
    }

    @Test
    void moveMovesStockBetweenLocations() throws Exception {
        receive("PO-5", 1, "ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 20, "park.jh");

        mockMvc.perform(post("/api/moves").with(httpBasic("park.jh", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveRef":"MOVE-1","warehouseCode":"ICN01","fromLocationCode":"A-01-01-1",
                                 "toLocationCode":"A-01-02-1","skuCode":"SKU-100001","lotNo":"DEFAULT","qty":8}
                                """))
                .andExpect(status().isOk());

        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(12);
        assertThat(onHandQty("ICN01", "A-01-02-1", "SKU-100001", "DEFAULT")).isEqualTo(8);
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────────

    private long receive(String poLineRef, int seq, String warehouseCode, String locationCode, String skuCode,
            String lotNo, int qty, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/receipts").with(httpBasic(username, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(receiptJson(poLineRef, seq, warehouseCode, locationCode, skuCode, lotNo, qty)))
                .andExpect(status().isOk())
                .andReturn();
        return txnIdFrom(result);
    }

    private String receiptJson(String poLineRef, int seq, String warehouseCode, String locationCode,
            String skuCode, String lotNo, int qty) {
        return """
                {"poLineRef":"%s","receiptSeq":%d,"warehouseCode":"%s","locationCode":"%s","skuCode":"%s",
                 "lotNo":"%s","qty":%d}
                """.formatted(poLineRef, seq, warehouseCode, locationCode, skuCode, lotNo, qty);
    }

    private String adjustmentJson(String ref, String warehouseCode, String locationCode, String skuCode,
            String lotNo, int qty, String reasonCode) {
        return """
                {"adjustmentRef":"%s","warehouseCode":"%s","locationCode":"%s","skuCode":"%s","lotNo":"%s",
                 "qty":%d,"reasonCode":"%s"}
                """.formatted(ref, warehouseCode, locationCode, skuCode, lotNo, qty, reasonCode);
    }

    private long txnIdFrom(MvcResult result) throws Exception {
        Number txnId = JsonPath.read(result.getResponse().getContentAsString(), "$.txnId");
        return txnId.longValue();
    }

    private int ledgerLineCount(long txnId) {
        return jdbc.sql("SELECT COUNT(*) FROM inventory_ledger_entry WHERE txn_id = :txnId")
                .param("txnId", txnId).query(Integer.class).single();
    }

    private int ledgerQtySum(long txnId) {
        return jdbc.sql("SELECT SUM(qty_delta) FROM inventory_ledger_entry WHERE txn_id = :txnId")
                .param("txnId", txnId).query(Integer.class).single();
    }

    private int txnCountForIdemKey(String idemKey) {
        return jdbc.sql("SELECT COUNT(*) FROM inventory_txn WHERE idem_key = :idemKey")
                .param("idemKey", idemKey).query(Integer.class).single();
    }

    private int onHandQty(String warehouseCode, String locationCode, String skuCode, String lotNo) {
        return jdbc.sql("""
                SELECT b.on_hand_qty
                FROM stock_balance b
                JOIN location loc ON loc.id = b.location_id JOIN warehouse w ON w.id = loc.warehouse_id
                JOIN sku s ON s.id = b.sku_id JOIN lot l ON l.id = b.lot_id
                WHERE w.code = :wh AND loc.code = :loc AND s.code = :sku AND l.lot_no = :lot
                """)
                .param("wh", warehouseCode)
                .param("loc", locationCode)
                .param("sku", skuCode)
                .param("lot", lotNo)
                .query(Integer.class)
                .single();
    }
}

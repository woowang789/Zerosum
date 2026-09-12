package com.zerosum.inventory.repository;

import com.zerosum.inventory.domain.AllocationId;
import com.zerosum.inventory.domain.PostingCommand;
import com.zerosum.inventory.domain.ResolvedLine;
import com.zerosum.inventory.domain.SkuId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 트랜잭셔널 아웃박스 기록. 릴레이·소비자는 2단계 이후 범위라 여기서는 기록만 한다 (docs/06-events-reconciliation.md).
 *
 * <p><b>파티션 키를 SKU별로 쪼갠 이유.</b> {@code outbox_event.partition_key}는 Kafka 키(예: sku_id)로 쓰인다
 * (docs/02-data-model.md). 그런데 거래 하나(inventory_txn)는 여러 SKU를 담을 수 있다. SKU 하나(예: 최솟값)만
 * 대표로 골라 파티션 키로 쓰면, 같은 거래에 딸린 다른 SKU의 이벤트가 그 SKU의 다른 거래들과는 다른 파티션으로
 * 흩어져 SKU별 순서 보장이 깨진다. 소비자(이상 탐지·수요 예측, 07-ai-integration.md)는 SKU 단위로 집계하므로
 * SKU별 순서가 거래 단위 순서보다 중요하다고 판단했다. 그래서 거래 하나를 SKU별로 묶어 이벤트를 여러 건
 * 내보내고, 각 이벤트는 자신이 담은 SKU만 파티션 키로 쓴다 — 대안(txnId를 파티션 키로 써서 거래 단위 순서만
 * 보장하고 SKU별 순서는 포기하는 것)은 고르지 않았다. 이벤트에는 원래 거래 id(txnId)를 그대로 넣어두어,
 * 필요하면 소비자가 같은 거래에서 갈라진 이벤트들을 다시 묶을 수 있게 했다.
 */
@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appendStockPosted(long txnId, PostingCommand cmd) {
        Map<Long, List<ResolvedLine>> entriesBySku = cmd.entries().stream()
                .collect(Collectors.groupingBy(e -> e.skuId().value(), LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<Long, List<ResolvedLine>> skuEntries : entriesBySku.entrySet()) {
            insertOneEvent(txnId, cmd, skuEntries.getKey(), skuEntries.getValue());
        }
    }

    // JSON 라이브러리를 새로 끌어오지 않고, entries 배열도 jsonb_build_array/jsonb_build_object로 SQL에서 직접 짠다.
    // 클래스패스의 tools.jackson(3.x)은 Hibernate가 끌어온 전이 의존성이라 우리 쪽에서 선언한 것이 아니므로 기대지 않았다.
    private void insertOneEvent(long txnId, PostingCommand cmd, long skuId, List<ResolvedLine> entries) {
        StringBuilder entriesExpr = new StringBuilder("jsonb_build_array(");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("partitionKey", String.valueOf(skuId));
        params.put("txnId", txnId);
        params.put("txnType", cmd.txnType());
        params.put("sourceRef", cmd.sourceRef());

        for (int i = 0; i < entries.size(); i++) {
            ResolvedLine entry = entries.get(i);
            if (i > 0) {
                entriesExpr.append(", ");
            }
            entriesExpr.append("""
                    jsonb_build_object(
                        'locationId', :locationId%1$d, 'locationCode', :locationCode%1$d,
                        'skuId', :skuId%1$d, 'skuCode', :skuCode%1$d,
                        'lotId', :lotId%1$d, 'lotNo', :lotNo%1$d,
                        'qtyDelta', :qtyDelta%1$d)
                    """.formatted(i));
            params.put("locationId" + i, entry.locationId().value());
            params.put("locationCode" + i, entry.locationCode());
            params.put("skuId" + i, entry.skuId().value());
            params.put("skuCode" + i, entry.skuCode());
            params.put("lotId" + i, entry.lotId().value());
            params.put("lotNo" + i, entry.lotNo());
            params.put("qtyDelta" + i, entry.qtyDelta());
        }
        entriesExpr.append(")");

        jdbc.sql("""
                INSERT INTO outbox_event (event_type, partition_key, payload)
                VALUES ('StockPosted', :partitionKey,
                        jsonb_build_object('txnId', :txnId, 'txnType', :txnType, 'sourceRef', :sourceRef,
                                           'entries', %s))
                """.formatted(entriesExpr))
                .params(params)
                .update();
    }

    /** db/04-harness.sql tst_allocate 끝의 StockAllocated 이벤트와 같다. 할당은 SKU 하나로 고정이라 쪼갤 필요가 없다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendStockAllocated(SkuId skuId, String orderLineRef, int qty, List<AllocationId> allocationIds) {
        StringBuilder idsExpr = new StringBuilder("jsonb_build_array(");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("partitionKey", String.valueOf(skuId.value()));
        params.put("orderLineRef", orderLineRef);
        params.put("qty", qty);
        for (int i = 0; i < allocationIds.size(); i++) {
            if (i > 0) {
                idsExpr.append(", ");
            }
            idsExpr.append(":id").append(i);
            params.put("id" + i, allocationIds.get(i).value());
        }
        idsExpr.append(')');

        jdbc.sql("""
                INSERT INTO outbox_event (event_type, partition_key, payload)
                VALUES ('StockAllocated', :partitionKey,
                        jsonb_build_object('orderLineRef', :orderLineRef, 'qty', :qty, 'allocationIds', %s))
                """.formatted(idsExpr))
                .params(params)
                .update();
    }
}

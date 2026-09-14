package com.zerosum.inventory.proposal;

import com.zerosum.inventory.domain.BalanceLine;
import com.zerosum.inventory.domain.BalanceObservation;
import com.zerosum.inventory.domain.LockedBalances;
import com.zerosum.inventory.domain.WarehouseSkuObservation;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * basis_snapshot 재검증 (docs/07-ai-integration.md 제안 실행 규칙). 오차 공식은 db/04-harness.sql
 * tst_approve_proposal과 글자 단위로 같다: {@code abs(현재 - 관측) > max(관측 * tolerancePct, 0)}이면
 * 허용 오차를 벗어난 것으로 본다.
 */
public class BasisRecheck {

    private final double tolerancePct;

    public BasisRecheck(double tolerancePct) {
        this.tolerancePct = tolerancePct;
    }

    /**
     * 잠글 수 없는 관측값(창고별 SKU 판매 가능 수량) 비교. 잔액 행을 잠그기 전, 포스팅 직전에 호출된다
     * (잠금을 쥔 채 추가 읽기를 하지 않기 위해서다).
     */
    public boolean warehouseSkuObservationsHold(List<WarehouseSkuObservation> observed,
            List<WarehouseSkuObservation> current) {
        Map<WarehouseSkuKey, Integer> currentByKey = current.stream()
                .collect(Collectors.toMap(o -> new WarehouseSkuKey(o.warehouseId(), o.skuId()),
                        WarehouseSkuObservation::sellableQty));
        for (WarehouseSkuObservation obs : observed) {
            Integer cur = currentByKey.get(new WarehouseSkuKey(obs.warehouseId(), obs.skuId()));
            if (!withinTolerance(cur != null ? cur : 0, obs.sellableQty())) {
                return false;
            }
        }
        return true;
    }

    /** 잔액 행 관측값 비교. 포스팅 서비스가 잔액 행을 FOR UPDATE로 잠근 직후 호출되는 선행 조건이다. */
    public Predicate<LockedBalances> balanceObservationsHold(List<BalanceObservation> observed) {
        return balances -> {
            List<BalanceLine> lines = balances.snapshot().lines();
            for (BalanceObservation obs : observed) {
                BalanceLine line = lines.stream()
                        .filter(l -> l.id().value() == obs.balanceId())
                        .findFirst()
                        .orElse(null);
                if (line == null) {
                    return false;
                }
                int current = line.onHandQty() - line.allocatedQty();
                if (!withinTolerance(current, obs.availableQty())) {
                    return false;
                }
            }
            return true;
        };
    }

    private boolean withinTolerance(int current, int observed) {
        return Math.abs(current - observed) <= Math.max(observed * tolerancePct, 0);
    }

    /**
     * 관측값 하나의 대조 결과 — 승인 화면이 basis_snapshot과 현재 값을 나란히 보여줄 때 쓴다(할 일 3).
     * {@code valid}는 withinTolerance()를 그대로 호출한 값이라, 여기서 유효로 보면 승인 시점 재검증도
     * 같은 결과를 낸다 — 비교 로직을 화면용으로 새로 쓰지 않기 위한 장치다.
     */
    public record Comparison(int observed, int current, int diff, double allowedDiff, boolean valid) {
    }

    /** observed(basis_snapshot 값)와 current(지금 값)를 대조한다. balance·warehouse_sku 스코프 공용. */
    public Comparison compare(int observed, int current) {
        return new Comparison(observed, current, current - observed, Math.max(observed * tolerancePct, 0),
                withinTolerance(current, observed));
    }

    private record WarehouseSkuKey(long warehouseId, long skuId) {
    }
}

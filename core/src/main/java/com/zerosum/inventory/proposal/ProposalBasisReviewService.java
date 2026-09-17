package com.zerosum.inventory.proposal;

import com.zerosum.inventory.domain.BalanceObservation;
import com.zerosum.inventory.domain.WarehouseSkuObservation;
import com.zerosum.inventory.repository.ProposalRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 승인 화면의 근거 대조(할 일 3) — basis_snapshot과 현재 값을 나란히 보여준다. 승인 트랜잭션 안에서 하는
 * 재검증(ProposalApprovalService)과 같은 결과를 내야 하므로, 오차 공식은 {@link BasisRecheck}를 그대로
 * 쓰고 여기서 새로 만들지 않는다 — 다르면 "화면은 유효한데 승인하면 STALE"이 된다.
 *
 * <p>여기서 읽는 현재 값은 잠그지 않은 스냅샷이다(ProposalRepository#currentBalances·
 * currentWarehouseSkuPreview). 그래서 화면은 어디까지나 미리보기이고, 실제 유효성 판단은 승인 트랜잭션이
 * (잔액 행을 잠근 뒤) 다시 내린다.
 */
@Service
public class ProposalBasisReviewService {

    public record BalanceReview(long balanceId, BasisRecheck.Comparison comparison) {
    }

    public record WarehouseSkuReview(long warehouseId, long skuId, BasisRecheck.Comparison comparison) {
    }

    public record BasisReview(List<BalanceReview> balance, List<WarehouseSkuReview> warehouseSku) {

        /** ProposalApprovalService가 실행 여부를 가르는 것과 같은 기준: 스코프 전부가 허용 오차 안이어야 한다. */
        public boolean allValid() {
            // 관측이 하나도 없으면 "전부 유효"가 아니라 "대조할 것이 없다"다. allMatch는 빈 목록에 참이라
            // 이 줄이 없으면 화면은 "근거 유효"라고 하는데 승인하면 STALE이 된다
            // (ProposalApprovalService ③-2). 두 판정은 같은 기준이어야 한다 — 이 클래스와 BasisRecheck의
            // javadoc이 그것을 명시적으로 요구한다.
            if (balance.isEmpty() && warehouseSku.isEmpty()) {
                return false;
            }
            return balance.stream().allMatch(r -> r.comparison().valid())
                    && warehouseSku.stream().allMatch(r -> r.comparison().valid());
        }
    }

    private final ProposalRepository proposalRepo;
    private final double tolerancePct;

    public ProposalBasisReviewService(ProposalRepository proposalRepo,
            @Value("${zerosum.proposal.basis.tolerance-pct}") double tolerancePct) {
        this.proposalRepo = proposalRepo;
        this.tolerancePct = tolerancePct;
    }

    public BasisReview review(long proposalId) {
        BasisRecheck recheck = new BasisRecheck(tolerancePct);
        ProposalRepository.BasisObservations basis = proposalRepo.basisObservationsOf(proposalId);

        List<Long> balanceIds = basis.balance().stream().map(BalanceObservation::balanceId).toList();
        Map<Long, Integer> currentBalances = proposalRepo.currentBalances(balanceIds).stream()
                .collect(Collectors.toMap(BalanceObservation::balanceId, BalanceObservation::availableQty));
        List<BalanceReview> balanceReviews = basis.balance().stream()
                .map(obs -> new BalanceReview(obs.balanceId(),
                        recheck.compare(obs.availableQty(), currentBalances.getOrDefault(obs.balanceId(), 0))))
                .toList();

        List<WarehouseSkuObservation> currentWarehouseSku = proposalRepo
                .currentWarehouseSkuPreview(basis.warehouseSku());
        Map<WarehouseSkuKey, Integer> currentByKey = currentWarehouseSku.stream()
                .collect(Collectors.toMap(o -> new WarehouseSkuKey(o.warehouseId(), o.skuId()),
                        WarehouseSkuObservation::sellableQty));
        List<WarehouseSkuReview> warehouseSkuReviews = basis.warehouseSku().stream()
                .map(obs -> new WarehouseSkuReview(obs.warehouseId(), obs.skuId(),
                        recheck.compare(obs.sellableQty(),
                                currentByKey.getOrDefault(new WarehouseSkuKey(obs.warehouseId(), obs.skuId()), 0))))
                .toList();

        return new BasisReview(balanceReviews, warehouseSkuReviews);
    }

    // BasisRecheck.WarehouseSkuKey는 private이라 여기서 못 쓴다 — 같은 모양의 키를 이 서비스 안에 따로 둔다.
    private record WarehouseSkuKey(long warehouseId, long skuId) {
    }
}

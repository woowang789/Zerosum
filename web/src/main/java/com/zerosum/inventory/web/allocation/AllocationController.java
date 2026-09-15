package com.zerosum.inventory.web.allocation;

import com.zerosum.inventory.allocation.AllocateRequest;
import com.zerosum.inventory.allocation.AllocationGateway;
import com.zerosum.inventory.allocation.AllocationResult;
import com.zerosum.inventory.domain.AllocationId;
import com.zerosum.inventory.web.security.AccessGuard;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 할당·할당 해제 창구. 둘 다 OPERATOR 이상. 멱등 키는 클라이언트가 보내지 않는다 — 생성은 요청의
 * {@code orderLineRef}(업무 식별자)에서, 해제는 대상 할당 id 집합 자체에서 서버가 파생한다.
 *
 * <p>생성은 요청 본문의 {@code warehouseCode}를 바로 검증하면 되지만, 해제는 할당 id만 받으므로
 * {@link com.zerosum.inventory.web.issue.IssueController}의 {@code {id}} 엔드포인트와 같은 패턴으로
 * 대상의 창고를 먼저 읽어와 대조한다 ({@link AllocationLookupRepository}).
 */
@RestController
@RequestMapping("/api/allocations")
public class AllocationController {

    private final AllocationGateway allocationGateway;
    private final AllocationLookupRepository allocationLookupRepo;

    AllocationController(AllocationGateway allocationGateway, AllocationLookupRepository allocationLookupRepo) {
        this.allocationGateway = allocationGateway;
        this.allocationLookupRepo = allocationLookupRepo;
    }

    @PostMapping
    public AllocateResponse allocate(@RequestBody AllocateRequestBody request, Authentication authentication) {
        AccessGuard.requireAnyRole(authentication, "OPERATOR", "SUPERVISOR");
        AccessGuard.requireWarehouse(authentication, request.warehouseCode());

        // 업무 식별자(주문라인)에서 파생 — 같은 주문라인을 다시 보내면 새 할당이 아니라 기존 결과를 돌려준다.
        String idemKey = "allocate:%s".formatted(request.orderLineRef());
        AllocationResult result = allocationGateway.allocate(new AllocateRequest(idemKey, request.orderLineRef(),
                request.warehouseCode(), request.skuCode(), request.qty(), request.allowInCount()));
        return new AllocateResponse(result.allocationIds().stream().map(AllocationId::value).toList());
    }

    @DeleteMapping
    public void release(@RequestBody ReleaseRequestBody request, Authentication authentication) {
        AccessGuard.requireAnyRole(authentication, "OPERATOR", "SUPERVISOR");

        // 정렬해 중복을 없앤 뒤 창고를 검사하고, 같은 정렬 순서로 idemKey를 파생하고 릴리스를 호출한다 —
        // 클라이언트가 id를 어떤 순서로 보내든 같은 집합이면 같은 키가 되게 한다.
        List<Long> ids = request.allocationIds().stream().distinct().sorted().toList();
        for (String warehouseCode : allocationLookupRepo.warehouseCodesOf(ids)) {
            AccessGuard.requireWarehouse(authentication, warehouseCode);
        }

        String idemKey = "release:%s".formatted(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        allocationGateway.release(idemKey, ids.stream().map(AllocationId::new).toList());
    }

    public record AllocateRequestBody(String orderLineRef, String warehouseCode, String skuCode, int qty,
            boolean allowInCount) {
    }

    public record AllocateResponse(List<Long> allocationIds) {
    }

    public record ReleaseRequestBody(List<Long> allocationIds) {
    }
}

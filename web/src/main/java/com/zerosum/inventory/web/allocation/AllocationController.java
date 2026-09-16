package com.zerosum.inventory.web.allocation;

import com.zerosum.inventory.allocation.AllocateRequest;
import com.zerosum.inventory.allocation.AllocationGateway;
import com.zerosum.inventory.allocation.AllocationResult;
import com.zerosum.inventory.domain.AllocationId;
import com.zerosum.inventory.web.error.InvalidRequestException;
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
 * 할당·할당 해제 창구. 둘 다 OPERATOR 이상. 멱등 키는 클라이언트가 보내지 않는다 — 해제는 대상 할당 id
 * 집합 자체에서 이 컨트롤러가 파생하고, 생성은 {@code allocate:{주문줄}:{회차}}의 회차를 트랜잭션 안에서
 * 세어야 해서 코어({@code AllocationService#allocate})가 파생한다.
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
        // 입고·출고·이동과 같은 이유로 여기서도 막는다(PostingController#requirePositiveQty). 코어도 스스로
        // 막지만(AllocationService), 창구에서 걸러야 400으로 "무엇이 잘못됐는지"를 말해줄 수 있다.
        if (request.qty() <= 0) {
            throw new InvalidRequestException("NON_POSITIVE_QTY", "할당 수량은 양수여야 한다: %d".formatted(request.qty()));
        }

        AllocationResult result = allocationGateway.allocate(new AllocateRequest(request.orderLineRef(),
                request.warehouseCode(), request.skuCode(), request.qty(), request.allowInCount()));
        List<Long> allocationIds = result.allocationIds().stream().map(AllocationId::value).toList();
        List<AllocationLine> lines = allocationLookupRepo.linesOf(allocationIds).stream()
                .map(line -> new AllocationLine(line.allocationId(), line.locationCode(), line.skuCode(),
                        line.lotNo(), line.qty()))
                .toList();
        return new AllocateResponse(allocationIds, lines);
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

    public record AllocateResponse(List<Long> allocationIds, List<AllocationLine> lines) {
    }

    /** 이 할당이 예약한 잔액 행(FEFO가 고른 로케이션·로트). 출고 화면이 그대로 줄로 채운다. */
    public record AllocationLine(long allocationId, String locationCode, String skuCode, String lotNo, int qty) {
    }

    public record ReleaseRequestBody(List<Long> allocationIds) {
    }
}

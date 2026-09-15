package com.zerosum.inventory.web.count;

import com.zerosum.inventory.count.AbandonCountRequest;
import com.zerosum.inventory.count.CountLineInput;
import com.zerosum.inventory.count.CountSessionGateway;
import com.zerosum.inventory.count.CountSubmitOutcome;
import com.zerosum.inventory.count.ResolveCountRequest;
import com.zerosum.inventory.count.StartCountRequest;
import com.zerosum.inventory.count.SubmitCountRequest;
import com.zerosum.inventory.web.security.AccessGuard;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실사 세션 창구(시작·제출·중단은 OPERATOR 이상, 정정 승인만 SUPERVISOR). 시작은 요청 본문의
 * {@code warehouseCode}를 바로 검증하지만, {@code {id}}만 받는 나머지 셋(제출·정정·중단)은
 * {@link com.zerosum.inventory.web.issue.IssueController}·{@link com.zerosum.inventory.web.proposal.ProposalController}와
 * 같은 패턴으로 세션의 창고를 {@link CountSessionLookupRepository}에서 읽어 대조한다.
 *
 * <p>멱등 키는 클라이언트가 보내지 않는다 — 시작은 (창고, 로케이션)에서, 나머지는 이미 서버가 만들어 준
 * 세션 id(업무 식별자)에서 서버가 파생한다. 실사자·제출자·정정자·중단자도 전부 {@link Authentication}에서만
 * 읽는다.
 */
@RestController
@RequestMapping("/api/counts")
public class CountController {

    private final CountSessionGateway countSessionGateway;
    private final CountSessionLookupRepository countSessionLookupRepo;

    CountController(CountSessionGateway countSessionGateway, CountSessionLookupRepository countSessionLookupRepo) {
        this.countSessionGateway = countSessionGateway;
        this.countSessionLookupRepo = countSessionLookupRepo;
    }

    @PostMapping
    public StartResponse start(@RequestBody StartRequest request, Authentication authentication) {
        AccessGuard.requireAnyRole(authentication, "OPERATOR", "SUPERVISOR");
        AccessGuard.requireWarehouse(authentication, request.warehouseCode());

        // 업무 식별자(창고, 로케이션)에서 파생 — 로케이션당 진행 중인 실사는 하나뿐이므로(I10) 재시도해도 같은
        // 세션을 돌려준다.
        String idemKey = "count:start:%s:%s".formatted(request.warehouseCode(), request.locationCode());
        long sessionId = countSessionGateway.start(
                new StartCountRequest(idemKey, request.warehouseCode(), request.locationCode(),
                        authentication.getName()));
        return new StartResponse(sessionId);
    }

    @PostMapping("/{id}/submit")
    public CountSubmitOutcome submit(@PathVariable long id, @RequestBody SubmitRequest request,
            Authentication authentication) {
        AccessGuard.requireAnyRole(authentication, "OPERATOR", "SUPERVISOR");
        AccessGuard.requireWarehouse(authentication, countSessionLookupRepo.warehouseCodeOf(id));

        String idemKey = "count:submit:%d".formatted(id);
        return countSessionGateway.submit(new SubmitCountRequest(idemKey, id, request.lines(),
                authentication.getName()));
    }

    @PostMapping("/{id}/resolve")
    public ResolveResponse resolve(@PathVariable long id, Authentication authentication) {
        AccessGuard.requireAnyRole(authentication, "SUPERVISOR");
        AccessGuard.requireWarehouse(authentication, countSessionLookupRepo.warehouseCodeOf(id));

        String idemKey = "count:resolve:%d".formatted(id);
        Long resolutionTxnId = countSessionGateway.resolve(
                new ResolveCountRequest(idemKey, id, authentication.getName()));
        return new ResolveResponse(resolutionTxnId);
    }

    @PostMapping("/{id}/abandon")
    public void abandon(@PathVariable long id, Authentication authentication) {
        AccessGuard.requireAnyRole(authentication, "OPERATOR", "SUPERVISOR");
        AccessGuard.requireWarehouse(authentication, countSessionLookupRepo.warehouseCodeOf(id));

        String idemKey = "count:abandon:%d".formatted(id);
        countSessionGateway.abandon(new AbandonCountRequest(idemKey, id, authentication.getName()));
    }

    public record StartRequest(String warehouseCode, String locationCode) {
    }

    public record StartResponse(long sessionId) {
    }

    public record SubmitRequest(List<CountLineInput> lines) {
    }

    public record ResolveResponse(Long resolutionTxnId) {
    }
}

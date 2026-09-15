package com.zerosum.inventory.web.stock;

import com.zerosum.inventory.web.security.WarehouseScope;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 원장 조회. 전 역할 허용 — 창고 범위만 {@link WarehouseScope}가 강제한다. {@code from}·{@code to}는
 * ISO-8601 순간(예: {@code 2026-09-01T00:00:00Z})이고 생략하면 그 경계를 걸지 않는다.
 */
@RestController
public class LedgerController {

    private static final int LIST_LIMIT = 500;

    private final LedgerQueryRepository ledgerRepo;

    LedgerController(LedgerQueryRepository ledgerRepo) {
        this.ledgerRepo = ledgerRepo;
    }

    @GetMapping("/api/ledger")
    public List<LedgerQueryRepository.LedgerRow> ledger(WarehouseScope warehouse,
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ledgerRepo.find(warehouse.code(), sku, location, parseInstant(from), parseInstant(to), LIST_LIMIT);
    }

    private static Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}

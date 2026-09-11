# AI 연결 지점

## 권한 경계

| DB 계정 | 접속 대상 | 권한 |
|---|---|---|
| migrator | 프라이머리 | 테이블 소유자. 애플리케이션에서 사용하지 않는다 |
| app_admin | 프라이머리 | 마스터(`warehouse`·`location`·`sku`·`lot`) 읽기·쓰기. 코어 테이블은 조회만 |
| app_rw | 프라이머리 | 코어 테이블 읽기·쓰기. `inventory_txn`, `inventory_ledger_entry`는 SELECT·INSERT만. 마스터는 조회만 하며 예외는 `location.count_session_id` 한 컬럼뿐이다 |
| ai_ro | 레플리카 | 조회용 뷰 SELECT만 |
| ai_proposer | 프라이머리 | `action_proposal` INSERT만 |

마스터 쓰기를 일상 쓰기 경로에서 떼어낸 이유는 사고의 성격이 다르기 때문이다. 로케이션을 하나 잘못 만들면 재고가 유령 로케이션으로 들어가고, SKU 코드를 잘못 고치면 이력이 통째로 어긋난다. 되돌리려면 조정 거래가 아니라 마이그레이션이 필요하다.

레플리카 지연 때문에 AI가 보는 재고는 몇 초 늦을 수 있다. 분석과 제안에는 문제가 없고, 실행 시점에 프라이머리에서 다시 검증하므로 정합성에도 영향이 없다.

AI 조회는 테이블이 아니라 뷰로 노출한다. 스키마가 바뀌어도 도구 계약이 유지되고, 노출할 컬럼을 통제할 수 있다.

```sql
CREATE VIEW v_available_stock AS
SELECT b.warehouse_id,
       b.sku_id,  s.code AS sku_code,  s.name AS sku_name,
       b.lot_id,  l.lot_no,            l.expiry_date,
       b.location_id, loc.code AS location_code,
       b.on_hand_qty, b.allocated_qty,
       b.on_hand_qty - b.allocated_qty AS available_qty,
       loc.count_session_id IS NOT NULL AS in_count   -- 실사 중이면 기본 판매 가능 수량에서 제외 (05-count-session.md)
FROM stock_balance b
JOIN location loc ON loc.id = b.location_id AND loc.is_sellable
JOIN sku s        ON s.id   = b.sku_id
JOIN lot l        ON l.id   = b.lot_id;

GRANT SELECT ON v_available_stock TO ai_ro;
```

MCP 서버는 조회 도구로 `get_available_stock`, `get_ledger`(SKU·로케이션·기간), `list_open_issues`, `get_issue_context`(이슈 전후 원장과 실사 이력 묶음)를 제공하고, 쓰기 도구는 `create_proposal` 하나만 제공한다. 창고 접근 권한은 LLM에게 맡기지 않고 도구 내부에서 호출자 기준으로 강제한다. 에이전트가 재시도로 같은 제안을 여러 번 넣어도 `uq_proposal_pending` 인덱스 때문에 대기 중인 제안은 하나만 남는다.

## 제안 실행 규칙

```java
// 의사 코드
@Transactional
public ApprovalResult approve(long proposalId, String approver) {
    ActionProposal p = proposalRepo.lockForUpdate(proposalId);            // 승인 연타·동시 승인을 직렬화
    if (p.status() != ProposalStatus.PENDING) {
        return new AlreadyDecided(p.status());
    }
    if (!p.expiresAt().isAfter(clock.instant())) {
        return proposalRepo.markExpired(p);
    }

    InventoryCommand cmd = p.commandPayload().toCommand(
            "proposal:" + p.id(),            // 멱등 키
            "PROPOSAL",                      // source_type
            Actor.user(approver),
            p.id());

    // 잠글 수 없는 관측값(창고 합계, 외부 발주 잔량)은 포스팅 전에 다시 조회해 비교
    if (!p.basisSnapshot().unlockedObservationsHold(reader, 0.10)) {
        return proposalRepo.markStale(p, approver);
    }
    // 커맨드 종류별 실행기: 일반 거래는 포스팅 서비스로, 실사 정정(RESOLVE_COUNT)은 실사 서비스로 보낸다
    // 잔액 행 관측값은 잔액 행을 잠근 직후에 비교 (다른 빈 호출이라 같은 트랜잭션에 합류)
    PostingOutcome outcome = executor.execute(cmd,
            locked -> p.basisSnapshot().balanceObservationsHold(locked, 0.10));

    return switch (outcome) {
        case Posted(long txnId) -> proposalRepo.markExecuted(p, approver, txnId);
        case PreconditionFailed(BalanceSnapshot current) -> proposalRepo.markStale(p, approver, current); // 원장 기록 없이 커밋
    };
}
```

승인은 제안 행 잠금으로 시작하므로 승인 버튼 연타나 동시 승인이 직렬화되고, 멱등 키 `proposal:{id}`와 `executed_txn_id` UNIQUE가 거래를 한 번만 만들게 한다.

basis 재검증은 포스팅 서비스가 잔액 행을 잠근 직후에 실행된다. 제안 시점과 승인 시점 사이에 재고가 허용 오차 이상 바뀌었다면 원장에 아무것도 쓰지 않고 제안을 STALE로 바꿔 커밋하며, 에이전트가 최신 데이터로 다시 제안하게 한다. 이 실패를 예외로 던지면 Spring의 트랜잭션 전파에서 전체가 롤백 전용으로 표시되어 STALE 기록까지 사라지므로, 예외가 아닌 결과 값으로 돌려준다.

`basis_snapshot`은 LLM이 채우지 않는다. 에이전트는 `create_proposal`을 호출할 때 무엇을 근거로 봤는지(잔액 행, 창고별 SKU 합계, 발주 잔량 등)만 지정하고, MCP 서버가 제안을 저장하는 순간 DB에서 직접 조회해 값과 조회 시각을 기록한다. LLM이 옮겨 적은 숫자는 틀릴 수 있기 때문이다. 형태는 `{"captured_at": ..., "observations": [{"scope": "balance", "balance_id": ..., "available_qty": ...}, ...]}`이며, scope는 `balance`, `warehouse_sku`, `po_line`처럼 근거의 종류를 나타낸다. 잔액 행 관측값은 잠금 아래에서 다시 비교하고, 잠글 수 없는 관측값은 포스팅 직전에 조회해 비교한다. 후자는 불변식이 아니라 제안이 아직 타당한지 보는 신선도 검사이며, 실제 정합은 잔액 행 잠금과 CHECK 제약이 지킨다.

승인자가 제안 내용을 고쳐야 하면 제안 행을 직접 수정하지 않는다. 원 제안은 REJECTED로 닫고 수정본을 사용자 명의의 새 제안으로 올려야 AI가 무엇을 틀렸는지 기록이 남는다. 입고 서류 제안은 문서 해시가 같은 PENDING·EXECUTED 제안이 이미 있으면 생성 단계에서 거부해 같은 서류의 이중 입고를 막는다.

AI가 만든 커맨드는 사람이 만든 커맨드와 똑같은 검증을 통과해야 한다. 수량, 로케이션, 로트 어느 것도 AI가 계산했으니 맞다고 가정하지 않는다.

## 기능별 데이터 매핑

| 기능 | 이 설계에서 쓰는 데이터 | 비고 |
|---|---|---|
| 1. 자연어 재고 조회 | `v_available_stock`, 원장 조회 뷰 | 창고 권한은 도구 내부에서 강제, 결과 건수 제한 |
| 2. 입고 서류 인식·발주 대사 | `RECEIPT_DRAFT` 제안 → `RECEIPT` 거래 | 발주 데이터는 외부 연동 또는 최소 테이블 추가 |
| 3. 상품 마스터 정제·채널 매핑 | `sku` (확장 시 `sku_alias` + pgvector) | 입수 단위 환산은 API 경계 규칙으로 재검증 |
| 4. 불일치 탐지·원인 추적 | `inventory_issue`, `on_hand_after`, `count_result` | 체인 검증이 최초 불일치 거래를 짚어 분석 범위를 좁힌다 |
| 5. 수요 예측 기반 발주 | `SHIPMENT` 원장의 일별 집계 | `occurred_at` 기준, 한국 시간 일자로 집계 |
| 6. 유통기한 임박 소진 | `lot.expiry_date`, `stock_balance` | FEFO 할당과 같은 데이터 |
| 7. 순환 실사 우선순위 | `count_result`, `ADJUSTMENT` 이력 | 실사 차이 발생 여부가 곧 학습 라벨 |
| 8. 슬로팅 최적화 | `SHIPMENT` 원장의 로케이션, 주문(`source_ref`) 단위 동시 출고 | 재배치안은 `MOVE` 제안으로 실행 |
| 9. 반품 검수 판정 | `RETURN_HOLD` 로케이션 | 판정 결과는 STORAGE 또는 DAMAGED로의 `MOVE` 제안 |
| 10. 승인 기반 운영 에이전트 | `action_proposal` → 포스팅 서비스 | [제안 실행 규칙](#제안-실행-규칙) 적용 |

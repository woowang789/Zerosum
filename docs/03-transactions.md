# 거래 유형

| 거래 | 출발 → 도착 | 원천 | 비고 |
|---|---|---|---|
| RECEIPT (입고) | V_SUPPLIER → RECEIVING | 발주 | 입고 서류 AI 인식 결과는 제안으로 들어온다 |
| MOVE (적치·이동) | RECEIVING → STORAGE, STORAGE → STORAGE, RETURN_HOLD → STORAGE 또는 DAMAGED | 작업 지시 | 가용 수량(실재고 − 할당량)만 옮길 수 있다 |
| SHIPMENT (출고) | STORAGE → V_CUSTOMER | 주문 | 같은 트랜잭션에서 할당을 소진한다 |
| RETURN (반품 입고) | V_CUSTOMER → RETURN_HOLD | 반품 요청 | 검수 후 MOVE로 처리한다 |
| ADJUSTMENT (조정) | STORAGE ↔ V_ADJUSTMENT | 실사, 파손, 분실 | 사유 코드 필수 |
| TRANSFER_OUT (센터 출발) | A.STORAGE → A.TRANSIT | 센터 간 이동 지시 | 운송 중 재고도 잔액으로 추적되어 사라지지 않는다 |
| TRANSFER_IN (센터 도착) | A.TRANSIT → B.RECEIVING | 도착 스캔 | 도착 수량 차이는 별도 ADJUSTMENT |
| REVERSAL (역분개) | 원거래의 반대 방향 | 정정 | 역분개도 음수 검사를 통과해야 한다 |

역분개가 음수 검사에 걸리는 경우, 예를 들어 이미 출고된 입고를 역분개하려는 경우는 실패하는 것이 맞다. 그 실패가 출고를 먼저 정리해야 한다는 정정 순서를 알려준다. 같은 이유로 할당된 재고는 조정으로 줄일 수 없으며, 실사에서 할당분까지 부족하면 할당 해제가 먼저 일어나 영향받는 주문을 명시적으로 처리하게 된다.

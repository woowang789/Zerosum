package com.zerosum.inventory.web.error;

import com.zerosum.inventory.count.CountSessionException;
import com.zerosum.inventory.domain.AllocationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@link ApiExceptionHandler}가 다루지 않는 코어 예외(할당·실사)를 같은 {@code {code, message}} 모양으로
 * 매핑한다. 새로 추가하는 쓰기 엔드포인트(posting·allocation·count)가 도입한 예외 타입이라 기존
 * ApiExceptionHandler를 고치는 대신 어드바이스를 하나 더 둔다 — Spring은 여러 @RestControllerAdvice를
 * 함께 적용하므로 문제없다.
 */
@RestControllerAdvice
public class WriteApiExceptionHandler {

    /** 할당·해제 비즈니스 오류(INSUFFICIENT_STOCK 등). ApiExceptionHandler#handlePosting과 같은 취급 — 상태 충돌. */
    @ExceptionHandler(AllocationException.class)
    public ResponseEntity<ApiExceptionHandler.ErrorResponse> handleAllocation(AllocationException ex) {
        return conflict(ex.code(), ex.getMessage());
    }

    /** 실사 세션 흐름 오류(COUNT_NOT_OPEN 등). 상태 충돌. */
    @ExceptionHandler(CountSessionException.class)
    public ResponseEntity<ApiExceptionHandler.ErrorResponse> handleCount(CountSessionException ex) {
        return conflict(ex.code(), ex.getMessage());
    }

    /** 웹 계층 자체의 요청 형태 검증 실패(가상 로케이션 직접 지정 등). 잘못된 요청이므로 400. */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiExceptionHandler.ErrorResponse> handleInvalid(InvalidRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiExceptionHandler.ErrorResponse(ex.code(), ex.getMessage()));
    }

    private static ResponseEntity<ApiExceptionHandler.ErrorResponse> conflict(String code, String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiExceptionHandler.ErrorResponse(code, message));
    }
}

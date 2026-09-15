package com.zerosum.inventory.web.error;

import com.zerosum.inventory.domain.IssueException;
import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.domain.ProposalException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 코어가 던지는 도메인 예외를 500이 아니라 의미 있는 상태 코드와 {@code {code, message}} JSON으로
 * 바꾼다. 창고·역할 거부({@code AccessDeniedException})는 Spring Security가 이미 403으로 처리하므로
 * 여기서 다루지 않는다(WebSecurityTest의 기존 동작을 그대로 잇는다).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorResponse(String code, String message) {
    }

    /** ProposalException·IssueException은 코드가 *_NOT_FOUND로 끝나면 404, 그 외엔 상태 충돌(409)이다. */
    @ExceptionHandler(ProposalException.class)
    public ResponseEntity<ErrorResponse> handleProposal(ProposalException ex) {
        return byCode(ex.code(), ex.getMessage());
    }

    @ExceptionHandler(IssueException.class)
    public ResponseEntity<ErrorResponse> handleIssue(IssueException ex) {
        return byCode(ex.code(), ex.getMessage());
    }

    /** 포스팅 단계 실패(예: COUNT_IN_PROGRESS)는 항상 상태 충돌이다. */
    @ExceptionHandler(PostingException.class)
    public ResponseEntity<ErrorResponse> handlePosting(PostingException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    // ProposalRepository#lockForUpdate처럼 단건 조회(.single())가 존재하지 않는 id를 만나면 코어가 아니라
    // 이 예외를 던진다 — 컨트롤러가 미리 존재를 확인하지 못한 경로에 대한 방어선이다.
    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EmptyResultDataAccessException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("NOT_FOUND", "대상을 찾을 수 없다"));
    }

    private static ResponseEntity<ErrorResponse> byCode(String code, String message) {
        HttpStatus status = code.endsWith("_NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }
}

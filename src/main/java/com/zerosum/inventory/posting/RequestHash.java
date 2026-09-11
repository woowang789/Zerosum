package com.zerosum.inventory.posting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.stream.Collectors;

/** 같은 멱등 키에 다른 본문이 오면 409로 거절하기 위한 요청 본문 해시 (idempotency_record.request_hash). */
final class RequestHash {

    private RequestHash() {
    }

    static String of(PostingRequest request) {
        String canonical = request.txnType() + "|"
                + request.lines().stream()
                        .map(l -> String.join(",", l.warehouseCode(), l.locationCode(), l.skuCode(), l.lotNo(),
                                String.valueOf(l.qty())))
                        .collect(Collectors.joining(";"))
                + "|" + Objects.toString(request.reasonCode(), "")
                + "|" + Objects.toString(request.sourceRef(), "")
                + "|" + Objects.toString(request.reversesTxnId(), "");
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // 모든 JVM이 SHA-256을 표준으로 제공한다 (JLS 필수 알고리즘)
            throw new IllegalStateException(e);
        }
    }
}

package com.zerosum.inventory.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 클라이언트 라우팅 대체 경로. React Router가 만드는 주소(/stock, /proposals ...)는 서버에 대응하는
 * 리소스가 없어 그대로 두면 404다 — 사용자가 새로고침하거나 링크로 바로 들어오면 앱이 사라진다.
 *
 * <p>확장자가 없는 경로만 index.html로 넘긴다. /assets/app-abc123.js 같은 실제 자산 요청이 여기로
 * 흘러들면 자바스크립트 대신 HTML이 돌아가 원인을 알기 어려운 오류가 난다. /api/**는 시큐리티가
 * 먼저 처리하므로 여기 오지 않는다.
 */
@Controller
public class SpaForwardingController {

    @GetMapping({"/", "/{path:[^.]*}", "/{path:[^.]*}/{sub:[^.]*}"})
    public String forward() {
        return "forward:/index.html";
    }
}

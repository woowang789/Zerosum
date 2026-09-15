package com.zerosum.inventory.web.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** {@code ?warehouse=} 를 읽어 인증 주체의 허용 목록과 대조한 뒤에만 {@link WarehouseScope}를 만든다. */
@Component
public class WarehouseScopeArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String PARAM = "warehouse";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return WarehouseScope.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest request, WebDataBinderFactory binderFactory) throws Exception {
        String code = request.getParameter(PARAM);
        if (code == null || code.isBlank()) {
            throw new MissingServletRequestParameterException(PARAM, "String");
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof WarehouseUser user)) {
            throw new AccessDeniedException("인증 주체에 창고 권한이 없다");
        }
        if (!user.canAccess(code)) {
            // 창고가 없다고 알려주지 않고 권한이 없다고만 한다 — 어느 창고가 존재하는지가 새지 않는다.
            throw new AccessDeniedException("창고 %s 접근 권한이 없다".formatted(code));
        }
        return new WarehouseScope(code);
    }
}

package com.blogsystem.identity.infrastructure.security;

import cn.dev33.satoken.stp.StpUtil;
import com.blogsystem.identity.application.IdentityApplicationFacade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Also rejects old ADMIN sessions on public endpoints that expose privileged read modes.
 */
@Component
public class SingleOwnerSessionInterceptor implements HandlerInterceptor {
    private final IdentityApplicationFacade identity;

    public SingleOwnerSessionInterceptor(IdentityApplicationFacade identity) {
        this.identity = identity;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("POST".equals(request.getMethod()) && "/api/auth/login/password".equals(request.getRequestURI()))
            return true;
        String token = StpUtil.getTokenValue();
        if (token != null && !token.isBlank()) {
            StpUtil.checkLogin();
            identity.requireCurrentOwner();
        }
        return true;
    }
}

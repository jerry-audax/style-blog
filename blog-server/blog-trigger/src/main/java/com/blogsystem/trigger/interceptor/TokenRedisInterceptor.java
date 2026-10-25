package com.blogsystem.trigger.interceptor;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;

import com.blogsystem.domain.identity.gateway.OwnerTokenVerifier;
import com.blogsystem.domain.identity.model.valobj.OwnerTokenVerificationException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

/** Translates inward verification outcomes to the existing HTTP authentication contract. */
public class TokenRedisInterceptor implements HandlerInterceptor {
    private final OwnerTokenVerifier verifier;

    public TokenRedisInterceptor(OwnerTokenVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = StpUtil.getTokenValue();
        if (token == null || token.isEmpty()) return true;
        try {
            verifier.verify(token);
        } catch (OwnerTokenVerificationException failure) {
            String reason =
                    failure.reason() == OwnerTokenVerificationException.Reason.REVOKED
                            ? "凭证已失效，请重新登录"
                            : "服务繁忙，请重新登录";
            // Preserve the existing constructor order and therefore the existing response
            // semantics.
            throw new NotLoginException("admin", "token", reason);
        }
        return true;
    }
}

package com.blogsystem.trigger.interceptor;

import com.blogsystem.domain.asset.publication.model.gateway.PublicationMachineCredentialVerifier;
import com.blogsystem.trigger.http.support.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public final class PublicationMachineInterceptor implements HandlerInterceptor {
    private final PublicationMachineCredentialVerifier authenticator;
    private final ObjectMapper json = new ObjectMapper();

    public PublicationMachineInterceptor(PublicationMachineCredentialVerifier authenticator) { this.authenticator = java.util.Objects.requireNonNull(authenticator); }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        response.setHeader("Cache-Control", "no-store");
        var headers = request.getHeaders("X-Publication-Key");
        String key = headers.hasMoreElements() ? headers.nextElement() : null;
        // 浏览器来源一律先拒绝，空Origin也不能因凭据错误而改变约定的403响应。
        int status = request.getHeader("Origin") != null ? 403
                : headers.hasMoreElements() || !authenticator.accepts(key) ? 401 : 0;
        if (status == 0) return true;
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), ApiResponse.fail(status, status == 401 ? "机器认证失败" : "不允许浏览器访问机器接口"));
        return false;
    }
}

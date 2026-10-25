package com.blogsystem.app.it.trigger.interceptor;

import com.blogsystem.trigger.interceptor.TokenRedisInterceptor;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import com.blogsystem.infrastructure.security.identity.RedisOwnerTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenRedisInterceptorTest {
    @Test
    void revokedTokenAndRedisProfileFailuresKeepTheirOutcomes() {
        check(Boolean.TRUE, false, new String[]{"prod"}, false, null);
        check(null, false, new String[]{"prod"}, false, null);
        check(Boolean.FALSE, false, new String[]{"prod"}, false, "凭证已失效");
        check(null, true, new String[]{"dev"}, false, null);
        check(null, true, new String[]{"prod"}, false, "服务繁忙");
        check(null, true, new String[0], false, "服务繁忙");
        check(null, true, new String[]{"prod", "dev"}, false, null);
        check(null, true, new String[0], true, null);
    }

    @Test
    void absentTokenDoesNotQueryRedis() {
        try (var tokens = mockStatic(StpUtil.class)) {
            for (String token : new String[]{null, ""}) {
                tokens.when(StpUtil::getTokenValue).thenReturn(token);
                var verifier = mock(com.blogsystem.domain.identity.gateway.OwnerTokenVerifier.class);
                assertTrue(new TokenRedisInterceptor(verifier).preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()));
                verifyNoInteractions(verifier);
            }
        }
    }

    private void check(Boolean present, boolean redisFails, String[] profiles, boolean environmentFails, String error) {
        var redis = mock(StringRedisTemplate.class);
        if (redisFails) when(redis.hasKey("Authorization:login:token:fixture-token")).thenThrow(new IllegalStateException("fake Redis unavailable"));
        else when(redis.hasKey("Authorization:login:token:fixture-token")).thenReturn(present);
        var environment = new MockEnvironment().withProperty("not-used", "fixture");
        environment.setActiveProfiles(profiles);
        var context = mock(WebApplicationContext.class);
        when(context.getBean(StringRedisTemplate.class)).thenReturn(redis);
        if (environmentFails) when(context.getBean(Environment.class)).thenThrow(new IllegalStateException("fake environment unavailable"));
        else when(context.getBean(Environment.class)).thenReturn(environment);
        var request = new MockHttpServletRequest();
        request.getServletContext().setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);
        try (var tokens = mockStatic(StpUtil.class)) {
            tokens.when(StpUtil::getTokenValue).thenReturn("fixture-token");
            var interceptor = new TokenRedisInterceptor(new RedisOwnerTokenVerifier(
                    () -> context.getBean(StringRedisTemplate.class), () -> context.getBean(Environment.class)));
            if (error == null) assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
            else {
                var failure = assertThrows(NotLoginException.class,
                        () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
                // Preserve the existing constructor argument order, even though the localized reason is its type.
                assertEquals("admin", failure.getMessage());
                assertTrue(failure.getType().contains(error));
            }
        }
        verify(redis).hasKey("Authorization:login:token:fixture-token");
    }
}

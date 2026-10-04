package com.blogsystem.identity.infrastructure.security;

import cn.dev33.satoken.exception.NotLoginException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT + Redis 二级校验拦截器。
 * Sa-Token JWT 模式只校验签名和过期，不查 Redis。
 * 此拦截器在 SaInterceptor 之后运行，额外检查 Redis 中是否仍存有该 token。
 * 管理员从 Redis 删除会话后，下一次请求立即被拦截返回 401。
 *
 * <p>Redis failure policy:
 * <ul>
 *   <li>dev profile: log WARN and allow through (lenient for local development)</li>
 *   <li>prod profile: throw NotLoginException to refuse the request</li>
 * </ul>
 */
@Slf4j
public class TokenRedisInterceptor implements HandlerInterceptor {

    private static final String REDIS_KEY_PREFIX = "Authorization:login:token:";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = cn.dev33.satoken.stp.StpUtil.getTokenValue();
        if (token == null || token.isEmpty()) return true;

        try {
            WebApplicationContext ctx = WebApplicationContextUtils
                    .getRequiredWebApplicationContext(request.getServletContext());
            StringRedisTemplate redis = ctx.getBean(StringRedisTemplate.class);
            if (Boolean.FALSE.equals(redis.hasKey(REDIS_KEY_PREFIX + token))) {
                throw new NotLoginException("admin", "token", "凭证已失效，请重新登录");
            }
        } catch (NotLoginException e) {
            throw e;
        } catch (Exception e) {
            // Redis unavailable — check profile to decide behavior
            try {
                WebApplicationContext ctx = WebApplicationContextUtils
                        .getRequiredWebApplicationContext(request.getServletContext());
                Environment env = ctx.getBean(Environment.class);
                String[] activeProfiles = env.getActiveProfiles();
                boolean isDev = false;
                for (String profile : activeProfiles) {
                    if ("dev".equals(profile)) {
                        isDev = true;
                        break;
                    }
                }
                if (isDev) {
                    log.warn("Redis 令牌校验异常（dev: 降级放行）", e);
                } else {
                    log.error("Redis 令牌校验异常（prod: 拒绝请求）", e);
                    throw new NotLoginException("admin", "token", "服务繁忙，请重新登录");
                }
            } catch (NotLoginException nle) {
                throw nle;
            } catch (Exception inner) {
                log.warn("无法获取运行环境配置，降级放行", inner);
            }
        }
        return true;
    }
}

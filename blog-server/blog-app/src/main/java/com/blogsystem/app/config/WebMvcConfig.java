package com.blogsystem.app.config;

import cn.dev33.satoken.interceptor.SaInterceptor;

import com.blogsystem.domain.identity.gateway.OwnerTokenVerifier;
import com.blogsystem.trigger.interceptor.SingleOwnerSessionInterceptor;
import com.blogsystem.trigger.interceptor.TokenRedisInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final SingleOwnerSessionInterceptor owner;
    private final OwnerTokenVerifier tokens;

    public WebMvcConfig(SingleOwnerSessionInterceptor owner, OwnerTokenVerifier tokens) {
        this.owner = owner;
        this.tokens = tokens;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(owner).addPathPatterns("/api/**")
                .excludePathPatterns("/api/content/home-tags");
        registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**");

        registry.addInterceptor(new TokenRedisInterceptor(tokens))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        // 认证相关公开接口
                        "/api/auth/login/password",
                        "/api/content/home-tags",
                        // 公开的文章接口
                        "/api/article/list",
                        "/api/article/hot",
                        "/api/article/*");
    }
}

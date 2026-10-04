package com.blogsystem.shared.infrastructure.configuration;

import cn.dev33.satoken.interceptor.SaInterceptor;
import com.blogsystem.identity.infrastructure.security.TokenRedisInterceptor;
import com.blogsystem.identity.infrastructure.security.SingleOwnerSessionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final SingleOwnerSessionInterceptor owner;

    public WebMvcConfig(SingleOwnerSessionInterceptor owner) {
        this.owner = owner;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(owner).addPathPatterns("/api/**");
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**");

        registry.addInterceptor(new TokenRedisInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        // 认证相关公开接口
                        "/api/auth/login/password",
                        // 公开的文章接口
                        "/api/article/list",
                        "/api/article/hot",
                        "/api/article/*"
                );
    }

}

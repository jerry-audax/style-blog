package com.blogsystem.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import com.blogsystem.security.TokenRedisInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${blog.upload.dir:uploads}")
    private String uploadDir;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/uploads/**");

        registry.addInterceptor(new TokenRedisInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        // 认证相关公开接口
                        "/api/auth/login",
                        "/api/auth/login/password",
                        "/api/auth/register",
                        "/api/auth/password/reset",
                        "/api/auth/sms-code",
                        // 公开的文章接口
                        "/api/article/list",
                        "/api/article/hot",
                        "/api/article/*"
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path p = Path.of(uploadDir);
        if (!p.isAbsolute()) {
            p = Path.of(System.getProperty("user.dir")).resolve(uploadDir);
        }
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(p.toAbsolutePath().toUri().toString());
    }
}

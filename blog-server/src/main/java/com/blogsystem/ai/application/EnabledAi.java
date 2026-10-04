package com.blogsystem.ai.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.*;

/**
 * Bootstrap gate, not a domain rule. Missing configuration means disabled.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "ai.enabled", havingValue = "true")
public @interface EnabledAi {
}

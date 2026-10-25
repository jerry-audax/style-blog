package com.blogsystem.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class DomainRuntimeConfiguration {
    @Bean
    public Clock domainClock() {
        return Clock.systemDefaultZone();
    }
}

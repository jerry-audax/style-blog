package com.blogsystem.shared.infrastructure.configuration;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainRuntimeConfiguration {
    @Bean
    public Clock domainClock() {
        return Clock.systemDefaultZone();
    }
}

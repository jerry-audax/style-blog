package com.blogsystem.identity.infrastructure.configuration;

import com.blogsystem.identity.domain.model.SingleOwnerPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OwnerIdentityConfiguration {
    @Bean
    SingleOwnerPolicy singleOwnerPolicy(@Value("${blog.owner.phone}") String phone) {
        return new SingleOwnerPolicy(phone);
    }
}

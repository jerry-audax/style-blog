package com.blogsystem.domain.identity.gateway;

public interface LoginProtection {
    void checkAttempt(String requestIp);
}

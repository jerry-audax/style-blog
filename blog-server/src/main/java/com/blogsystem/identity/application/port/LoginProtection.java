package com.blogsystem.identity.application.port;

public interface LoginProtection {
    void checkAttempt(String requestIp);
}

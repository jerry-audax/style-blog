package com.blogsystem.domain.identity.gateway;

public interface OwnerCredentials {
    boolean matches(String raw, String encoded);

    String encode(String raw);

    boolean needsUpgrade(String encoded);
}

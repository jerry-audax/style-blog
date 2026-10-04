package com.blogsystem.identity.application.port;

public interface OwnerCredentials {
    boolean matches(String raw, String encoded);

    String encode(String raw);

    boolean needsUpgrade(String encoded);
}

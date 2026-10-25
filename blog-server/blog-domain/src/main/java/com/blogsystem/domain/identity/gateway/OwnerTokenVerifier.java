package com.blogsystem.domain.identity.gateway;

/** Verifies that an otherwise authenticated owner token is still available. */
public interface OwnerTokenVerifier {
    void verify(String token);
}

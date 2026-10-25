package com.blogsystem.infrastructure.security.identity;

import com.blogsystem.infrastructure.security.identity.BcryptOwnerCredentials;
import com.blogsystem.infrastructure.security.identity.RedisLoginProtection;

import com.blogsystem.domain.identity.model.OwnerAccessException.Reason;

import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;

import com.blogsystem.domain.identity.model.OwnerAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnerCredentialAdapterTest {
    final BcryptOwnerCredentials credentials = new BcryptOwnerCredentials();

    @Test
    void bcryptMatchesOnlyCorrectPassword() {
        String hash = credentials.encode("fixture-secret");
        assertTrue(credentials.matches("fixture-secret", hash));
        assertFalse(credentials.matches("wrong", hash));
        assertFalse(credentials.needsUpgrade(hash));
    }

    @Test
    void legacyMd5IsAcceptedOnlyWithCorrectPasswordAndRequiresUpgrade() {
        assertTrue(credentials.matches("admin", "21232f297a57a5a743894a0e4a801fc3"));
        assertFalse(credentials.matches("wrong", "21232f297a57a5a743894a0e4a801fc3"));
        assertTrue(credentials.needsUpgrade("legacy-hash"));
    }

    @Test
    void missingOrCorruptPasswordsCannotMatch() {
        assertFalse(credentials.matches("fixture", null));
        assertFalse(credentials.matches("fixture", ""));
        assertFalse(credentials.matches("fixture", "$2-corrupt"));
    }

    @Test
    void redisFailureDoesNotDisableBruteForceProtection() {
        var redis = mock(StringRedisTemplate.class);
        assertEquals(OwnerAccessException.Reason.UNAVAILABLE, assertThrows(OwnerAccessException.class, () -> new RedisLoginProtection(redis).checkAttempt("ip")).reason());
    }
}

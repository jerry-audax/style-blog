package com.blogsystem.identity.infrastructure.security;

import com.blogsystem.identity.application.port.OwnerCredentials;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

@Component
public class BcryptOwnerCredentials implements OwnerCredentials {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public boolean matches(String raw, String encoded) {
        if (raw == null || encoded == null || encoded.isBlank()) return false;
        if (encoded.startsWith("$2")) {
            try {
                return encoder.matches(raw, encoded);
            } catch (IllegalArgumentException invalidHash) {
                return false;
            }
        }
        String digest = DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII), encoded.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public String encode(String raw) {
        return encoder.encode(raw);
    }

    @Override
    public boolean needsUpgrade(String encoded) {
        return !encoded.startsWith("$2");
    }
}

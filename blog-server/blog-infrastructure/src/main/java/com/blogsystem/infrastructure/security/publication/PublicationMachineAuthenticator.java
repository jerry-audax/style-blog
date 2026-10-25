package com.blogsystem.infrastructure.security.publication;

import com.blogsystem.domain.asset.publication.model.gateway.PublicationMachineCredentialVerifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.function.Supplier;

public final class PublicationMachineAuthenticator implements PublicationMachineCredentialVerifier {
    private final Supplier<String> credential;

    public PublicationMachineAuthenticator(Supplier<String> credential) { this.credential = Objects.requireNonNull(credential); }

    public void requireConfigured() {
        if (configured().length() < 32) throw new IllegalStateException("发布机器凭据未配置");
    }

    @Override public boolean accepts(String provided) {
        try {
            String expected = configured();
            if (expected.length() < 32 || provided == null) return false;
            // 比较定长摘要，避免不同密钥长度影响比较路径；不保留或输出调用方密钥。
            var sha = MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(sha.digest(expected.getBytes(StandardCharsets.UTF_8)),
                    sha.digest(provided.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | RuntimeException failure) { return false; }
    }

    private String configured() { String value = credential.get(); return value == null ? "" : value.strip(); }
}

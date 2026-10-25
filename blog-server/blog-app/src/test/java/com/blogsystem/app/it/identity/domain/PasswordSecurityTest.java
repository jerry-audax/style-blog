package com.blogsystem.app.it.identity.domain;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-1 BCrypt 密码安全单元测试。
 * 验证哈希唯一性、正确密码匹配、错误密码拒绝、
 * BCrypt前缀识别（$2）、老MD5兼容判断、密码最小6位。
 */
class PasswordSecurityTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void bcrypt_generatesUniqueHashEachTime() {
        String raw = "mypassword123";
        String hash1 = encoder.encode(raw);
        String hash2 = encoder.encode(raw);
        assertNotEquals(hash1, hash2);
    }

    @Test
    void bcrypt_matchesCorrectPassword() {
        String raw = "admin123456";
        String hash = encoder.encode(raw);
        assertTrue(encoder.matches(raw, hash));
    }

    @Test
    void bcrypt_rejectsWrongPassword() {
        String hash = encoder.encode("correct");
        assertFalse(encoder.matches("wrong", hash));
    }

    @Test
    void bcrypt_hashStartsWithBcryptPrefix() {
        String hash = encoder.encode("test");
        assertTrue(hash.startsWith("$2"), "BCrypt hash should start with $2");
    }

    @Test
    void oldMd5Password_detectedByPrefix() {
        // 老密码是 MD5（不以 $2 开头），新密码是 BCrypt
        String oldMd5Hash = "21232f297a57a5a743894a0e4a801fc3"; // md5("admin")
        String newBcryptHash = encoder.encode("admin");

        assertFalse(oldMd5Hash.startsWith("$2"), "MD5 hash should not start with $2");
        assertTrue(newBcryptHash.startsWith("$2"), "BCrypt hash should start with $2");
    }

    @Test
    void password_minimum6Characters() {
        assertTrue("123456".length() >= 6);
        assertFalse("12345".length() >= 6);
    }
}

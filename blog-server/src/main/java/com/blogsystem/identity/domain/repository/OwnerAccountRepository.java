package com.blogsystem.identity.domain.repository;

import com.blogsystem.identity.domain.model.OwnerAccount;

import java.util.Optional;

public interface OwnerAccountRepository {
    Optional<OwnerAccount> findByPhone(String phone);

    Optional<OwnerAccount> findById(long id);

    void recordSuccessfulLogin(long id, String upgradedHash);

    OwnerAccount updateProfile(long id, String nickname, String email, String avatar);

    void changePassword(long id, String encodedPassword);

    void recordLoginAttempt(OwnerAccount account, boolean success, String ip, String userAgent);
}

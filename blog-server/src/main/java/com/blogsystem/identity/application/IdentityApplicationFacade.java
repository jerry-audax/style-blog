package com.blogsystem.identity.application;

import com.blogsystem.identity.domain.model.*;
import com.blogsystem.identity.domain.repository.OwnerAccountRepository;
import com.blogsystem.identity.application.port.*;
import com.blogsystem.identity.application.command.PasswordLoginCommand;
import com.blogsystem.identity.application.view.OwnerSessionView;
import org.springframework.stereotype.Service;

import static com.blogsystem.identity.domain.model.OwnerAccessException.Reason.*;

/**
 * Single-owner use cases; no persistence technology or Sa-Token in this layer.
 */
@Service
public class IdentityApplicationFacade {
    private final SingleOwnerPolicy policy;
    private final OwnerAccountRepository accounts;
    private final OwnerCredentials credentials;
    private final OwnerSession session;
    private final LoginProtection protection;

    public IdentityApplicationFacade(SingleOwnerPolicy policy, OwnerAccountRepository accounts,
                                     OwnerCredentials credentials, OwnerSession session, LoginProtection protection) {
        this.policy = policy;
        this.accounts = accounts;
        this.credentials = credentials;
        this.session = session;
        this.protection = protection;
    }

    public OwnerSessionView loginByPassword(PasswordLoginCommand request, String ip, String userAgent) {
        protection.checkAttempt(ip);
        OwnerAccount account = policy.acceptsPhone(request.phone()) ? accounts.findByPhone(request.phone()).orElse(null) : null;
        if (!policy.accepts(account) || !credentials.matches(request.password(), account.passwordHash())) {
            accounts.recordLoginAttempt(account, false, ip, userAgent);
            throw new OwnerAccessException(INVALID_CREDENTIALS);
        }
        accounts.recordSuccessfulLogin(account.id(), credentials.needsUpgrade(account.passwordHash()) ? credentials.encode(request.password()) : null);
        String token = session.login(account.id());
        accounts.recordLoginAttempt(account, true, ip, userAgent);
        return view(account, token);
    }

    public OwnerAccount requireCurrentOwner() {
        OwnerAccount account = accounts.findById(session.currentUserId()).orElse(null);
        if (!policy.accepts(account)) throw new OwnerAccessException(OWNER_ONLY);
        if (!session.passwordAuthenticated()) throw new OwnerAccessException(PASSWORD_REQUIRED);
        return account;
    }

    public OwnerSessionView currentUser() {
        return view(requireCurrentOwner(), session.token());
    }

    public OwnerSessionView updateProfile(String nickname, String email, String avatar) {
        OwnerAccount account = requireCurrentOwner();
        return view(accounts.updateProfile(account.id(), nickname, email, avatar), session.token());
    }

    public void changePassword(String oldPassword, String newPassword) {
        OwnerAccount account = requireCurrentOwner();
        if (!credentials.matches(oldPassword, account.passwordHash()))
            throw new OwnerAccessException(INVALID_CREDENTIALS);
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 64)
            throw new IllegalArgumentException("新密码长度需要 8-64 位");
        if (newPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("新密码的 UTF-8 长度不能超过 72 字节");
        accounts.changePassword(account.id(), credentials.encode(newPassword));
        session.revoke(account.id());
    }

    public void logout() {
        session.logout();
    }

    private OwnerSessionView view(OwnerAccount account, String token) {
        return new OwnerSessionView(account.id(), account.phone(), account.username(), account.nickname(), account.email(), account.avatar(), token, true);
    }
}

package com.blogsystem.application.identity.service;

import com.blogsystem.domain.identity.gateway.LoginProtection;
import com.blogsystem.domain.identity.gateway.OwnerCredentials;
import com.blogsystem.domain.identity.gateway.OwnerSession;
import com.blogsystem.domain.identity.model.OwnerAccessException;
import com.blogsystem.domain.identity.model.OwnerAccount;
import com.blogsystem.domain.identity.model.SingleOwnerPolicy;

import com.blogsystem.domain.identity.adapter.repository.OwnerAccountRepository;
import com.blogsystem.application.identity.command.PasswordLoginCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SingleOwnerAuthenticationTest {
    final String phone = "13900000000";
    OwnerAccountRepository accounts;
    OwnerCredentials credentials;
    OwnerSession session;
    LoginProtection protection;
    IdentityApplicationFacade service;

    OwnerAccount owner(boolean active) {
        return new OwnerAccount(42, phone, "owner", "博主", null, null, "old-hash", active);
    }

    @BeforeEach
    void setup() {
        accounts = mock(OwnerAccountRepository.class);
        credentials = mock(OwnerCredentials.class);
        session = mock(OwnerSession.class);
        protection = mock(LoginProtection.class);
        service = new IdentityApplicationFacade(new SingleOwnerPolicy(phone), accounts, credentials, session, protection);
        when(accounts.findByPhone(phone)).thenReturn(Optional.of(owner(true)));
        when(accounts.findById(42)).thenReturn(Optional.of(owner(true)));
        when(session.currentUserId()).thenReturn(42L);
        when(session.passwordAuthenticated()).thenReturn(true);
    }

    @Test
    void correctOwnerPasswordIssuesSessionAndUpgradesLegacyHash() {
        when(credentials.matches("fixture", "old-hash")).thenReturn(true);
        when(credentials.needsUpgrade("old-hash")).thenReturn(true);
        when(credentials.encode("fixture")).thenReturn("bcrypt-hash");
        when(session.login(42)).thenReturn("fixture-token");
        var result = service.loginByPassword(new PasswordLoginCommand(phone, "fixture"), "127.0.0.1", "fixture");
        assertTrue(result.owner());
        assertEquals(42, result.userId());
        verify(protection).checkAttempt("127.0.0.1");
        verify(accounts).recordSuccessfulLogin(42, "bcrypt-hash");
        verify(session).login(42);
    }

    @Test
    void otherPhoneCannotAuthenticateEvenIfItWasPreviouslyAdmin() {
        var error = assertThrows(OwnerAccessException.class, () -> service.loginByPassword(new PasswordLoginCommand("13800000000", "fixture"), "ip", "ua"));
        assertEquals(OwnerAccessException.Reason.INVALID_CREDENTIALS, error.reason());
        verify(accounts, never()).findByPhone(anyString());
        verifyNoInteractions(session, credentials);
    }

    @Test
    void incorrectPasswordNeverIssuesSession() {
        assertThrows(OwnerAccessException.class, () -> service.loginByPassword(new PasswordLoginCommand(phone, "wrong"), "ip", "ua"));
        verify(session, never()).login(anyLong());
        verify(accounts, never()).recordSuccessfulLogin(anyLong(), any());
    }

    @Test
    void disabledOrDeletedOwnerCannotLogin() {
        when(accounts.findByPhone(phone)).thenReturn(Optional.of(owner(false)));
        assertThrows(OwnerAccessException.class, () -> service.loginByPassword(new PasswordLoginCommand(phone, "fixture"), "ip", "ua"));
        verifyNoInteractions(credentials, session);
    }

    @Test
    void missingOwnerCannotLogin() {
        when(accounts.findByPhone(phone)).thenReturn(Optional.empty());
        assertThrows(OwnerAccessException.class, () -> service.loginByPassword(new PasswordLoginCommand(phone, "fixture"), "ip", "ua"));
        verifyNoInteractions(credentials, session);
    }

    @Test
    void staleOtherAccountSessionIsDenied() {
        when(accounts.findById(42)).thenReturn(Optional.of(new OwnerAccount(42, "13800000000", "old-admin", null, null, null, "hash", true)));
        assertEquals(OwnerAccessException.Reason.OWNER_ONLY, assertThrows(OwnerAccessException.class, service::requireCurrentOwner).reason());
    }

    @Test
    void disabledOwnerSessionIsDenied() {
        when(accounts.findById(42)).thenReturn(Optional.of(owner(false)));
        assertThrows(OwnerAccessException.class, service::requireCurrentOwner);
    }

    @Test
    void legacyOwnerSmsSessionAlsoRequiresPasswordReauthentication() {
        when(session.passwordAuthenticated()).thenReturn(false);
        assertEquals(OwnerAccessException.Reason.PASSWORD_REQUIRED, assertThrows(OwnerAccessException.class, service::requireCurrentOwner).reason());
    }

    @Test
    void throttlingFailsBeforeCredentialLookup() {
        doThrow(new OwnerAccessException(OwnerAccessException.Reason.RATE_LIMITED)).when(protection).checkAttempt("ip");
        assertThrows(OwnerAccessException.class, () -> service.loginByPassword(new PasswordLoginCommand(phone, "fixture"), "ip", "ua"));
        verifyNoInteractions(accounts, credentials, session);
    }

    @Test
    void passwordChangeRevokesAllOwnerSessions() {
        when(credentials.matches("old", "old-hash")).thenReturn(true);
        when(credentials.encode("new-fixture-password")).thenReturn("new-hash");
        service.changePassword("old", "new-fixture-password");
        verify(accounts).changePassword(42, "new-hash");
        verify(session).revoke(42);
    }

    @Test
    void wrongOldPasswordAndShortNewPasswordDoNotWrite() {
        assertThrows(OwnerAccessException.class, () -> service.changePassword("wrong", "new-fixture-password"));
        when(credentials.matches("old", "old-hash")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.changePassword("old", "short"));
        verify(accounts, never()).changePassword(anyLong(), anyString());
        verify(session, never()).revoke(anyLong());
    }

    @Test
    void credentialSnapshotCannotLeakHashInToString() {
        assertFalse(owner(true).toString().contains("old-hash"));
    }
}

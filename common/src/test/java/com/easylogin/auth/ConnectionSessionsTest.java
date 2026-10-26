package com.easylogin.auth;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionSessionsTest {
    private final UUID account = UUID.randomUUID();
    private final ConnectionSessions<Object> sessions = new ConnectionSessions<>();

    @Test
    void unknownConnectionsAreDeniedEvenWhenTheyKnowTheUuid() {
        Object connection = new Object();
        assertFalse(sessions.isCurrent(account, connection));
        assertFalse(sessions.isAuthenticated(account, connection));
        assertFalse(sessions.authenticate(account, connection));
        assertFalse(sessions.authenticate(account, null));
    }

    @Test
    void replacementConnectionCannotInheritThePreviousLogin() {
        Object oldConnection = new Object();
        Object replacement = new Object();
        sessions.begin(account, oldConnection);
        assertTrue(sessions.authenticate(account, oldConnection));

        sessions.begin(account, replacement);

        assertFalse(sessions.isAuthenticated(account, replacement));
        assertFalse(sessions.isAuthenticated(account, oldConnection));
        assertFalse(sessions.authenticate(account, oldConnection));
        assertTrue(sessions.authenticate(account, replacement));
        assertFalse(sessions.isAuthenticated(account, oldConnection));
    }

    @Test
    void delayedOldDisconnectAndLogoutDoNotTouchReplacementSession() {
        Object oldConnection = new Object();
        Object replacement = new Object();
        sessions.begin(account, oldConnection);
        sessions.authenticate(account, oldConnection);
        sessions.begin(account, replacement);
        sessions.authenticate(account, replacement);

        assertFalse(sessions.end(account, oldConnection));
        assertFalse(sessions.deauthenticate(account, oldConnection));

        assertTrue(sessions.isCurrent(account, replacement));
        assertTrue(sessions.isAuthenticated(account, replacement));
    }

    @Test
    void logoutImmediatelyLocksTheSameConnectionAndAllowsAnotherLogin() {
        Object connection = new Object();
        sessions.begin(account, connection);
        sessions.authenticate(account, connection);

        assertTrue(sessions.deauthenticate(account, connection));
        assertFalse(sessions.isAuthenticated(account, connection));
        assertTrue(sessions.isCurrent(account, connection));
        assertTrue(sessions.authenticate(account, connection));
        assertTrue(sessions.isAuthenticated(account, connection));
    }

    @Test
    void administrativeRevocationLocksWithoutLosingConnectionOwnership() {
        Object connection = new Object();
        sessions.begin(account, connection);
        sessions.authenticate(account, connection);

        sessions.revoke(account);

        assertFalse(sessions.isAuthenticated(account, connection));
        assertTrue(sessions.isCurrent(account, connection));
        assertTrue(sessions.authenticate(account, connection));
    }

    @Test
    void normalDisconnectPreventsLateAuthenticationAndReconnectStartsLocked() {
        Object connection = new Object();
        sessions.begin(account, connection);
        sessions.authenticate(account, connection);
        assertTrue(sessions.end(account, connection));
        assertFalse(sessions.authenticate(account, connection));
        assertFalse(sessions.isAuthenticated(account, connection));

        sessions.begin(account, connection);
        assertFalse(sessions.isAuthenticated(account, connection));
    }

    @Test
    void connectionIdentityCannotBeSpoofedWithEquality() {
        Object original = new String("same-value");
        Object impostor = new String("same-value");
        assertEquals(original, impostor);
        assertNotSame(original, impostor);
        sessions.begin(account, original);
        sessions.authenticate(account, original);

        assertFalse(sessions.isAuthenticated(account, impostor));
        assertFalse(sessions.authenticate(account, impostor));
        assertFalse(sessions.end(account, impostor));
        assertTrue(sessions.isAuthenticated(account, original));
    }

    @Test
    void aNewPlayerObjectCanUseTheSameListenerAcrossRespawn() {
        Object listener = new Object();
        record PlayerView(UUID uuid, Object connection) {}
        PlayerView beforeRespawn = new PlayerView(account, listener);
        PlayerView afterRespawn = new PlayerView(account, listener);
        sessions.begin(beforeRespawn.uuid(), beforeRespawn.connection());
        sessions.authenticate(beforeRespawn.uuid(), beforeRespawn.connection());

        assertTrue(sessions.isAuthenticated(afterRespawn.uuid(), afterRespawn.connection()));
    }

    @Test
    void authenticationForOneAccountNeverAuthenticatesAnother() {
        Object first = new Object();
        Object second = new Object();
        UUID anotherAccount = UUID.randomUUID();
        sessions.begin(account, first);
        sessions.begin(anotherAccount, second);
        sessions.authenticate(account, first);

        assertFalse(sessions.isAuthenticated(anotherAccount, first));
        assertFalse(sessions.isAuthenticated(anotherAccount, second));
        assertFalse(sessions.authenticate(anotherAccount, first));
    }

    @Test
    void serverShutdownDiscardsAllSessions() {
        Object connection = new Object();
        sessions.begin(account, connection);
        sessions.authenticate(account, connection);

        sessions.clear();

        assertFalse(sessions.isCurrent(account, connection));
        assertFalse(sessions.isAuthenticated(account, connection));
        assertFalse(sessions.authenticate(account, connection));
    }

    @Test
    void delayedLoginCannotAuthenticateReplacementConnection() {
        Object oldConnection = new Object();
        Object replacement = new Object();
        sessions.begin(account, oldConnection);
        var pendingLogin = sessions.capture(account, oldConnection);

        sessions.begin(account, replacement);

        assertFalse(sessions.isCurrent(pendingLogin));
        assertFalse(sessions.authenticate(pendingLogin));
        assertFalse(sessions.isAuthenticated(account, replacement));
    }

    @Test
    void delayedLoginCannotUndoLogoutOnTheSameConnection() {
        Object connection = new Object();
        sessions.begin(account, connection);
        var pendingLogin = sessions.capture(account, connection);
        sessions.authenticate(account, connection);
        sessions.deauthenticate(account, connection);

        assertFalse(sessions.authenticate(pendingLogin));
        assertFalse(sessions.isAuthenticated(account, connection));
        assertTrue(sessions.authenticate(sessions.capture(account, connection)));
    }

    @Test
    void logoutInvalidatesPendingAuthenticatedWork() {
        Object connection = new Object();
        sessions.begin(account, connection);
        sessions.authenticate(account, connection);
        var passwordChange = sessions.capture(account, connection);

        sessions.deauthenticate(account, connection);

        assertFalse(sessions.isCurrent(passwordChange));
        assertTrue(sessions.isCurrent(account, connection));
    }

    @Test
    void onlyOneConcurrentLoginCompletionCanUnlockAGeneration() {
        Object connection = new Object();
        sessions.begin(account, connection);
        var firstLogin = sessions.capture(account, connection);
        var secondLogin = sessions.capture(account, connection);

        assertTrue(sessions.authenticate(firstLogin));
        assertFalse(sessions.authenticate(secondLogin));
        assertFalse(sessions.isCurrent(firstLogin));
        assertTrue(sessions.isAuthenticated(account, connection));
    }
}

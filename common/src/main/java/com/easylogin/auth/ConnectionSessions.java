package com.easylogin.auth;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Connection identity and generation checks for asynchronous authentication work. */
public final class ConnectionSessions<C> {
    private final Map<UUID, Session<C>> sessions = new HashMap<>();

    private static final class Session<C> {
        private final C connection;
        private Object generation = new Object();
        private boolean authenticated;

        private Session(C connection) {
            this.connection = connection;
        }
    }

    /** Captures one connection's current authorization generation, not merely its UUID. */
    public static final class Token<C> {
        private final UUID uuid;
        private final Session<C> session;
        private final Object generation;

        private Token(UUID uuid, Session<C> session) {
            this.uuid = uuid;
            this.session = session;
            this.generation = session.generation;
        }
    }

    /** Every join, including reconnects using the same UUID, starts locked. */
    public synchronized Token<C> begin(UUID uuid, C connection) {
        Session<C> session = new Session<>(Objects.requireNonNull(connection));
        sessions.put(Objects.requireNonNull(uuid), session);
        return new Token<>(uuid, session);
    }

    public synchronized boolean isCurrent(UUID uuid, C connection) {
        Session<C> session = sessions.get(uuid);
        return connection != null && session != null && session.connection == connection;
    }

    public synchronized boolean isAuthenticated(UUID uuid, C connection) {
        return isCurrent(uuid, connection) && sessions.get(uuid).authenticated;
    }

    public synchronized Token<C> capture(UUID uuid, C connection) {
        return isCurrent(uuid, connection) ? new Token<>(uuid, sessions.get(uuid)) : null;
    }

    public synchronized boolean isCurrent(Token<C> token) {
        return token != null && sessions.get(token.uuid) == token.session
                && token.generation == token.session.generation;
    }

    /** Completing one login invalidates any other pending login for that generation. */
    public synchronized boolean authenticate(Token<C> token) {
        if (!isCurrent(token)) return false;
        token.session.authenticated = true;
        token.session.generation = new Object();
        return true;
    }

    public synchronized boolean authenticate(UUID uuid, C connection) {
        return authenticate(capture(uuid, connection));
    }

    /** Logout invalidates pending callbacks even when the connection itself is unchanged. */
    public synchronized boolean deauthenticate(UUID uuid, C connection) {
        if (!isCurrent(uuid, connection)) return false;
        revoke(uuid);
        return true;
    }

    /** A delayed old disconnect must not erase a replacement connection. */
    public synchronized boolean end(UUID uuid, C connection) {
        if (!isCurrent(uuid, connection)) return false;
        sessions.remove(uuid);
        return true;
    }

    public synchronized void revoke(UUID uuid) {
        Session<C> session = sessions.get(uuid);
        if (session != null) {
            session.authenticated = false;
            session.generation = new Object();
        }
    }

    public synchronized void clear() {
        sessions.clear();
    }
}

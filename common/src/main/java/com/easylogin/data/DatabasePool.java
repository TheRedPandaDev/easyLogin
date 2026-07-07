package com.easylogin.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DatabasePool {
    private final String url;
    private final String user;
    private final String password;
    private final int poolSize;
    private final int timeoutSeconds;
    private final BlockingQueue<Connection> pool;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private volatile boolean closed = false;

    public DatabasePool(String url, String user, String password, int poolSize, int timeoutSeconds) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.poolSize = poolSize;
        this.timeoutSeconds = timeoutSeconds;
        this.pool = new ArrayBlockingQueue<>(poolSize);

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("com.mysql.jdbc.Driver");
            } catch (ClassNotFoundException ex) {
                // Ignore driver loading issue if already registered
            }
        }
    }

    public Connection getConnection() throws SQLException {
        if (closed) {
            throw new SQLException("Connection pool is closed.");
        }
        long limitTime = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < limitTime) {
            Connection conn = pool.poll();
            if (conn != null) {
                try {
                    if (conn.isValid(1)) {
                        return wrapConnection(conn);
                    } else {
                        closeQuietly(conn);
                        activeConnections.decrementAndGet();
                    }
                } catch (SQLException e) {
                    closeQuietly(conn);
                    activeConnections.decrementAndGet();
                }
            }

            int currentActive = activeConnections.get();
            if (currentActive < poolSize) {
                if (activeConnections.compareAndSet(currentActive, currentActive + 1)) {
                    try {
                        DriverManager.setLoginTimeout(timeoutSeconds);
                        Connection newConn = DriverManager.getConnection(url, user, password);
                        return wrapConnection(newConn);
                    } catch (SQLException e) {
                        activeConnections.decrementAndGet();
                        throw e;
                    }
                }
            }

            try {
                long waitTime = limitTime - System.currentTimeMillis();
                if (waitTime <= 0) break;
                conn = pool.poll(waitTime, TimeUnit.MILLISECONDS);
                if (conn != null) {
                    if (conn.isValid(1)) {
                        return wrapConnection(conn);
                    } else {
                        closeQuietly(conn);
                        activeConnections.decrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for connection", e);
            }
        }
        throw new SQLException("Timeout waiting for database connection (" + timeoutSeconds + " seconds)");
    }

    private Connection wrapConnection(Connection conn) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
            DatabasePool.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if (method.getName().equals("close")) {
                    if (!closed) {
                        pool.offer(conn);
                    } else {
                        closeQuietly(conn);
                        activeConnections.decrementAndGet();
                    }
                    return null;
                }
                try {
                    return method.invoke(conn, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        );
    }

    private void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (SQLException e) {
            // Ignore
        }
    }

    public synchronized void shutdown() {
        closed = true;
        Connection conn;
        while ((conn = pool.poll()) != null) {
            closeQuietly(conn);
        }
        activeConnections.set(0);
    }
}

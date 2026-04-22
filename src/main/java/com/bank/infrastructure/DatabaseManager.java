package com.bank.infrastructure;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Database connection manager using HikariCP.
 * <p>
 * Manages a thread-safe connection pool with explicit lifecycle control.
 * All JDBC interactions go through this class — no ORM magic.
 * </p>
 */
public final class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private static volatile HikariDataSource dataSource;

    private DatabaseManager() {}

    /**
     * Initialize the connection pool. Must be called once at application startup.
     */
    public static synchronized void init(String jdbcUrl, String user, String password) {
        if (dataSource != null) {
            log.warn("DatabaseManager already initialized — skipping.");
            return;
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        config.setIdleTimeout(30_000);
        config.setConnectionTimeout(5_000);
        config.setPoolName("BankAccountPool");

        // PostgreSQL-specific optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        dataSource = new HikariDataSource(config);
        log.info("HikariCP connection pool initialized (max={}).", config.getMaximumPoolSize());
    }

    /**
     * Obtain a connection from the pool.
     * Caller is responsible for closing it (use try-with-resources).
     */
    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new IllegalStateException("DatabaseManager not initialized. Call init() first.");
        }
        return dataSource.getConnection();
    }

    public static DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Shutdown the pool gracefully.
     */
    public static void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("HikariCP connection pool shut down.");
        }
    }

    // ─── Transaction helper ──────────────────────────────────────────────────

    /**
     * Executes a callback within a database transaction.
     * Auto-commits on success, rolls back on failure, always returns the connection.
     */
    public static <T> T withTransaction(TransactionCallback<T> callback) throws SQLException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = callback.execute(conn);
                conn.commit();
                return result;
            } catch (Exception ex) {
                conn.rollback();
                if (ex instanceof SQLException sqle) throw sqle;
                if (ex instanceof RuntimeException rte) throw rte;
                throw new RuntimeException(ex);
            }
        }
    }

    @FunctionalInterface
    public interface TransactionCallback<T> {
        T execute(Connection conn) throws Exception;
    }
}

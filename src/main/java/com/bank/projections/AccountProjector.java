package com.bank.projections;

import com.bank.infrastructure.DatabaseManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * CQRS Projector — maintains read-model tables from the event stream.
 * <p>
 * Demonstrates multithreading: the projection rebuild runs asynchronously
 * on a dedicated Virtual Thread executor (Java 21 Project Loom).
 * </p>
 */
public class AccountProjector {

    private static final Logger log = LoggerFactory.getLogger(AccountProjector.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Virtual-thread-based executor for async projection rebuilds.
     * Virtual threads are lightweight (millions can run concurrently)
     * — ideal for I/O-bound projection tasks.
     */
    private static final ExecutorService virtualExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    // ─── Project Events (synchronous, within a transaction) ──────────────────

    @SuppressWarnings("unchecked")
    public void projectEvents(List<Map<String, Object>> savedEventRows, Connection conn) throws SQLException {
        for (Map<String, Object> row : savedEventRows) {
            Map<String, Object> eventData = (Map<String, Object>) row.get("event_data");
            String eventType = (String) row.get("event_type");
            long globalSeq = row.get("global_sequence") != null
                    ? ((Number) row.get("global_sequence")).longValue()
                    : 0;
            Timestamp timestamp = (Timestamp) row.get("timestamp");

            applyToAccountSummaries(conn, eventType, eventData, globalSeq);
            applyToTransactionHistory(conn, eventType, eventData, globalSeq, timestamp);
            updateCheckpoint(conn, globalSeq);
        }
    }

    // ─── Account Summaries Projection ────────────────────────────────────────

    private void applyToAccountSummaries(Connection conn, String eventType,
                                          Map<String, Object> data, long globalSeq) throws SQLException {
        switch (eventType) {
            case "AccountCreated" -> {
                String sql = """
                    INSERT INTO account_summaries (account_id, owner_name, balance, currency, status, version)
                    VALUES (?, ?, ?, ?, 'OPEN', ?)
                    ON CONFLICT (account_id) DO UPDATE SET
                        owner_name = EXCLUDED.owner_name,
                        balance    = EXCLUDED.balance,
                        currency   = EXCLUDED.currency,
                        status     = EXCLUDED.status,
                        version    = EXCLUDED.version
                    """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, (String) data.get("accountId"));
                    ps.setString(2, (String) data.get("ownerName"));
                    ps.setBigDecimal(3, toBigDecimal(data.get("initialBalance")));
                    ps.setString(4, (String) data.get("currency"));
                    ps.setLong(5, globalSeq);
                    ps.executeUpdate();
                }
            }
            case "MoneyDeposited" -> {
                String sql = """
                    UPDATE account_summaries
                    SET balance = balance + ?, version = ?
                    WHERE account_id = ?
                    """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setBigDecimal(1, toBigDecimal(data.get("amount")));
                    ps.setLong(2, globalSeq);
                    ps.setString(3, (String) data.get("accountId"));
                    ps.executeUpdate();
                }
            }
            case "MoneyWithdrawn" -> {
                String sql = """
                    UPDATE account_summaries
                    SET balance = balance - ?, version = ?
                    WHERE account_id = ?
                    """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setBigDecimal(1, toBigDecimal(data.get("amount")));
                    ps.setLong(2, globalSeq);
                    ps.setString(3, (String) data.get("accountId"));
                    ps.executeUpdate();
                }
            }
            case "AccountClosed" -> {
                String sql = """
                    UPDATE account_summaries
                    SET status = 'CLOSED', version = ?
                    WHERE account_id = ?
                    """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, globalSeq);
                    ps.setString(2, (String) data.get("accountId"));
                    ps.executeUpdate();
                }
            }
            default -> { }
        }
    }

    // ─── Transaction History Projection ──────────────────────────────────────

    private void applyToTransactionHistory(Connection conn, String eventType,
                                            Map<String, Object> data, long globalSeq,
                                            Timestamp timestamp) throws SQLException {
        switch (eventType) {
            case "MoneyDeposited" -> insertTransaction(conn, data, "DEPOSIT", timestamp);
            case "MoneyWithdrawn" -> insertTransaction(conn, data, "WITHDRAWAL", timestamp);
            default -> { }
        }
    }

    private void insertTransaction(Connection conn, Map<String, Object> data,
                                    String type, Timestamp timestamp) throws SQLException {
        String sql = """
            INSERT INTO transaction_history (transaction_id, account_id, type, amount, description, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (transaction_id) DO NOTHING
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, (String) data.get("transactionId"));
            ps.setString(2, (String) data.get("accountId"));
            ps.setString(3, type);
            ps.setBigDecimal(4, toBigDecimal(data.get("amount")));
            ps.setString(5, (String) data.get("description"));
            ps.setTimestamp(6, timestamp);
            ps.executeUpdate();
        }
    }

    // ─── Checkpoint ──────────────────────────────────────────────────────────

    private void updateCheckpoint(Connection conn, long globalSeq) throws SQLException {
        String sql = """
            UPDATE projection_checkpoints
            SET last_processed_event_id = GREATEST(last_processed_event_id, ?), updated_at = NOW()
            WHERE projection_name IN ('AccountSummaries', 'TransactionHistory')
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, globalSeq);
            ps.executeUpdate();
        }
    }

    // ─── Full Rebuild (async via Virtual Threads) ────────────────────────────

    /**
     * Kicks off a full projection rebuild on a Virtual Thread.
     * Returns a Future so the caller can optionally await completion.
     */
    public Future<Integer> rebuildAllProjectionsAsync() {
        return virtualExecutor.submit(this::rebuildAllProjections);
    }

    /**
     * Synchronous rebuild — replays every event in global order.
     */
    @SuppressWarnings("unchecked")
    public int rebuildAllProjections() {
        try {
            return DatabaseManager.withTransaction(conn -> {
                // Clear read models
                execute(conn, "DELETE FROM account_summaries");
                execute(conn, "DELETE FROM transaction_history");
                execute(conn, "UPDATE projection_checkpoints SET last_processed_event_id = 0, updated_at = NOW()");

                // Replay all events in global order
                String sql = "SELECT * FROM events ORDER BY global_sequence ASC";
                List<Map<String, Object>> allEvents = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("event_type", rs.getString("event_type"));
                        row.put("global_sequence", rs.getLong("global_sequence"));
                        row.put("timestamp", rs.getTimestamp("timestamp"));

                        String jsonData = rs.getString("event_data");
                        Map<String, Object> eventData = mapper.readValue(jsonData, new TypeReference<>() {});
                        row.put("event_data", eventData);

                        allEvents.add(row);
                    }
                }

                for (Map<String, Object> row : allEvents) {
                    Map<String, Object> eventData = (Map<String, Object>) row.get("event_data");
                    String eventType = (String) row.get("event_type");
                    long globalSeq = ((Number) row.get("global_sequence")).longValue();
                    Timestamp timestamp = (Timestamp) row.get("timestamp");

                    applyToAccountSummaries(conn, eventType, eventData, globalSeq);
                    applyToTransactionHistory(conn, eventType, eventData, globalSeq, timestamp);
                    updateCheckpoint(conn, globalSeq);
                }

                log.info("Projection rebuild complete. Processed {} events.", allEvents.size());
                return allEvents.size();
            });
        } catch (SQLException e) {
            log.error("Projection rebuild failed.", e);
            throw new RuntimeException("Projection rebuild failed", e);
        }
    }

    // ─── Projection Status ───────────────────────────────────────────────────

    public Map<String, Object> getProjectionStatus() throws SQLException {
        int totalEvents;
        long maxSeq;

        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) as count FROM events");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                totalEvents = rs.getInt("count");
            }

            try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(global_sequence), 0) as max_seq FROM events");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                maxSeq = rs.getLong("max_seq");
            }

            List<Map<String, Object>> projections = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM projection_checkpoints");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> proj = new HashMap<>();
                    long lastProcessed = rs.getLong("last_processed_event_id");
                    proj.put("name", rs.getString("projection_name"));
                    proj.put("lastProcessedEventNumberGlobal", lastProcessed);
                    proj.put("lag", Math.max(0, maxSeq - lastProcessed));
                    projections.add(proj);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("totalEventsInStore", totalEvents);
            result.put("projections", projections);
            return result;
        }
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private void execute(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(value.toString());
    }
}

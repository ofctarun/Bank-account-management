package com.bank.queries;

import com.bank.domain.BankAccount;
import com.bank.infrastructure.DatabaseManager;
import com.bank.infrastructure.EventStore;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Query Handlers — the read-side of CQRS.
 * <p>
 * Reads from the projected read models (account_summaries, transaction_history)
 * for fast, denormalized queries. Also supports time-travel via event replay.
 * </p>
 */
public class QueryHandlers {

    private final EventStore eventStore;

    public QueryHandlers(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    // ─── Get Account Summary ─────────────────────────────────────────────────

    public Map<String, Object> getAccount(String accountId) throws SQLException {
        String sql = """
            SELECT account_id, owner_name, balance, currency, status
            FROM account_summaries
            WHERE account_id = ?
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("accountId", rs.getString("account_id"));
                result.put("ownerName", rs.getString("owner_name"));
                result.put("balance", rs.getBigDecimal("balance"));
                result.put("currency", rs.getString("currency"));
                result.put("status", rs.getString("status"));
                return result;
            }
        }
    }

    // ─── Get Event Stream ────────────────────────────────────────────────────

    public List<Map<String, Object>> getEventStream(String accountId) throws SQLException {
        String sql = """
            SELECT event_id, event_type, event_number, event_data, timestamp
            FROM events
            WHERE aggregate_id = ?
            ORDER BY event_number ASC
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> events = new ArrayList<>();
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                while (rs.next()) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("eventId", rs.getString("event_id"));
                    event.put("eventType", rs.getString("event_type"));
                    event.put("eventNumber", rs.getInt("event_number"));
                    // Parse JSONB to Map
                    String jsonData = rs.getString("event_data");
                    try {
                        event.put("data", mapper.readValue(jsonData,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                    } catch (Exception e) {
                        event.put("data", new HashMap<>());
                    }
                    event.put("timestamp", rs.getTimestamp("timestamp"));
                    events.add(event);
                }
                return events;
            }
        }
    }

    // ─── Time-Travel: Balance At Timestamp ───────────────────────────────────

    public Map<String, Object> getBalanceAt(String accountId, String timestamp) throws SQLException {
        // Check if account exists
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) as count FROM events WHERE aggregate_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt("count") == 0) return null;
            }
        }

        // Load events up to the timestamp and replay
        List<Map<String, Object>> events = eventStore.loadEventsBefore(accountId, timestamp);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", accountId);

        if (events.isEmpty()) {
            result.put("balanceAt", BigDecimal.ZERO);
        } else {
            BankAccount account = BankAccount.fromEvents(events);
            result.put("balanceAt", account.getBalance());
        }

        result.put("timestamp", timestamp);
        return result;
    }

    // ─── Paginated Transaction History ───────────────────────────────────────

    public Map<String, Object> getTransactions(String accountId, int page, int pageSize) throws SQLException {
        page = Math.max(1, page);
        pageSize = Math.max(1, pageSize);
        int offset = (page - 1) * pageSize;

        try (Connection conn = DatabaseManager.getConnection()) {
            // Count total
            int totalCount;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) as count FROM transaction_history WHERE account_id = ?")) {
                ps.setString(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    totalCount = rs.getInt("count");
                }
            }

            int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / pageSize));

            // Fetch page
            String sql = """
                SELECT transaction_id, type, amount, description, timestamp
                FROM transaction_history
                WHERE account_id = ?
                ORDER BY timestamp DESC
                LIMIT ? OFFSET ?
                """;
            List<Map<String, Object>> items = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, accountId);
                ps.setInt(2, pageSize);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("transactionId", rs.getString("transaction_id"));
                        item.put("type", rs.getString("type"));
                        item.put("amount", rs.getBigDecimal("amount"));
                        item.put("description", rs.getString("description"));
                        item.put("timestamp", rs.getTimestamp("timestamp"));
                        items.add(item);
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("currentPage", page);
            result.put("pageSize", pageSize);
            result.put("totalPages", totalPages);
            result.put("totalCount", totalCount);
            result.put("items", items);
            return result;
        }
    }
}

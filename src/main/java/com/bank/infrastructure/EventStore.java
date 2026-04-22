package com.bank.infrastructure;

import com.bank.domain.BankAccount;
import com.bank.domain.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Event Store — raw JDBC implementation.
 * <p>
 * Handles all persistence for the event-sourced aggregate:
 * loading/saving events, managing snapshots, and providing
 * time-travel queries. Zero ORM, pure SQL.
 * </p>
 */
public class EventStore {

    private static final Logger log = LoggerFactory.getLogger(EventStore.class);
    private static final int SNAPSHOT_THRESHOLD = 50;
    private static final ObjectMapper mapper = new ObjectMapper();

    // ─── Load Aggregate ──────────────────────────────────────────────────────

    /**
     * Loads the aggregate by replaying events from the store.
     * Uses snapshot optimization when available.
     */
    public BankAccount loadAggregate(String aggregateId) throws SQLException {
        Map<String, Object> snapshot = loadSnapshot(aggregateId);

        if (snapshot != null) {
            int fromEventNumber = ((Number) snapshot.get("last_event_number")).intValue() + 1;
            List<Map<String, Object>> events = loadEvents(aggregateId, fromEventNumber);
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshotData = (Map<String, Object>) snapshot.get("snapshot_data");
            return BankAccount.fromSnapshot(snapshotData, events);
        } else {
            List<Map<String, Object>> events = loadEvents(aggregateId, 1);
            if (events.isEmpty()) return null;
            return BankAccount.fromEvents(events);
        }
    }

    // ─── Load Events ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> loadEvents(String aggregateId, int fromEventNumber) throws SQLException {
        String sql = """
            SELECT event_id, aggregate_id, event_type, event_data, event_number, timestamp, version
            FROM events
            WHERE aggregate_id = ? AND event_number >= ?
            ORDER BY event_number ASC
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, aggregateId);
            ps.setInt(2, fromEventNumber);
            return extractEventRows(ps.executeQuery());
        }
    }

    public List<Map<String, Object>> loadAllEvents(String aggregateId) throws SQLException {
        String sql = """
            SELECT event_id, aggregate_id, event_type, event_data, event_number, timestamp, version, global_sequence
            FROM events
            WHERE aggregate_id = ?
            ORDER BY event_number ASC
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, aggregateId);
            return extractEventRows(ps.executeQuery());
        }
    }

    public List<Map<String, Object>> loadEventsBefore(String aggregateId, String timestamp) throws SQLException {
        String sql = """
            SELECT event_id, aggregate_id, event_type, event_data, event_number, timestamp, version
            FROM events
            WHERE aggregate_id = ? AND timestamp <= ?::timestamptz
            ORDER BY event_number ASC
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, aggregateId);
            ps.setString(2, timestamp);
            return extractEventRows(ps.executeQuery());
        }
    }

    // ─── Save Events (within a transaction) ──────────────────────────────────

    /**
     * Persists pending domain events using the provided transactional connection.
     * Returns the saved event rows (with generated IDs and global_sequence).
     */
    public List<Map<String, Object>> saveEvents(Connection conn, String aggregateId,
                                                 String aggregateType,
                                                 List<DomainEvent> pendingEvents,
                                                 int currentVersion) throws SQLException {
        String sql = """
            INSERT INTO events
                (event_id, aggregate_id, aggregate_type, event_type, event_data, event_number, version, global_sequence)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, 1, nextval('events_global_seq'))
            RETURNING *
            """;

        List<Map<String, Object>> savedEvents = new ArrayList<>();
        int eventNumber = currentVersion + 1;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (DomainEvent event : pendingEvents) {
                String eventId = UUID.randomUUID().toString();
                ps.setObject(1, UUID.fromString(eventId));
                ps.setString(2, aggregateId);
                ps.setString(3, aggregateType);
                ps.setString(4, event.eventType());
                ps.setString(5, mapper.writeValueAsString(event.data()));
                ps.setInt(6, eventNumber);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        savedEvents.add(extractRow(rs));
                    }
                }
                eventNumber++;
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }

        return savedEvents;
    }

    // ─── Snapshot Management ─────────────────────────────────────────────────

    public Map<String, Object> loadSnapshot(String aggregateId) throws SQLException {
        String sql = "SELECT * FROM snapshots WHERE aggregate_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, aggregateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> result = new HashMap<>();
                result.put("last_event_number", rs.getInt("last_event_number"));
                String jsonStr = rs.getString("snapshot_data");
                result.put("snapshot_data", mapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {}));
                return result;
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to deserialize snapshot", e);
            }
        }
    }

    public void saveSnapshot(Connection conn, String aggregateId,
                             Map<String, Object> snapshotData, int lastEventNumber) throws SQLException {
        String sql = """
            INSERT INTO snapshots (snapshot_id, aggregate_id, snapshot_data, last_event_number, created_at)
            VALUES (?::uuid, ?, ?::jsonb, ?, NOW())
            ON CONFLICT (aggregate_id)
            DO UPDATE SET snapshot_data = EXCLUDED.snapshot_data,
                          last_event_number = EXCLUDED.last_event_number,
                          created_at = NOW()
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, aggregateId);
            ps.setString(3, mapper.writeValueAsString(snapshotData));
            ps.setInt(4, lastEventNumber);
            ps.executeUpdate();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize snapshot", e);
        }
    }

    /**
     * Auto-snapshot check: triggers snapshot when version crosses the threshold boundary.
     */
    public void maybeSnapshot(Connection conn, BankAccount account,
                              int prevVersion, int newVersion) throws SQLException {
        if (newVersion / SNAPSHOT_THRESHOLD > prevVersion / SNAPSHOT_THRESHOLD) {
            saveSnapshot(conn, account.getAccountId(), account.toSnapshot(), newVersion);
            log.debug("Snapshot taken for {} at version {}.", account.getAccountId(), newVersion);
        }
    }

    // ─── Query Helpers ───────────────────────────────────────────────────────

    public int getTotalEventCount() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) as count FROM events");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt("count");
        }
    }

    public long getMaxGlobalSequence() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(global_sequence), 0) as max_seq FROM events");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong("max_seq");
        }
    }

    // ─── Internal Row Extraction ─────────────────────────────────────────────

    private List<Map<String, Object>> extractEventRows(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            rows.add(extractRow(rs));
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("event_id", rs.getString("event_id"));
        row.put("aggregate_id", rs.getString("aggregate_id"));
        row.put("event_type", rs.getString("event_type"));
        row.put("event_number", rs.getInt("event_number"));
        row.put("timestamp", rs.getTimestamp("timestamp"));
        row.put("version", rs.getInt("version"));

        // Parse JSONB event_data into a Map
        String jsonData = rs.getString("event_data");
        try {
            Map<String, Object> eventData = mapper.readValue(jsonData, new TypeReference<>() {});
            row.put("event_data", eventData);
        } catch (JsonProcessingException e) {
            row.put("event_data", new HashMap<>());
            log.warn("Failed to parse event_data JSON for event {}", rs.getString("event_id"), e);
        }

        // global_sequence may not be in every query
        try {
            row.put("global_sequence", rs.getLong("global_sequence"));
        } catch (SQLException ignored) {
            // Column not present in this query — that's fine
        }

        return row;
    }
}

package com.bank.commands;

import com.bank.domain.BankAccount;
import com.bank.domain.DomainEvent;
import com.bank.domain.DomainException;
import com.bank.infrastructure.DatabaseManager;
import com.bank.infrastructure.EventStore;
import com.bank.projections.AccountProjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Command Handlers — the write-side of CQRS.
 * <p>
 * Each handler loads the aggregate, invokes a domain command,
 * persists the resulting events, and updates projections — all
 * within a single database transaction for consistency.
 * </p>
 */
public class CommandHandlers {

    private static final Logger log = LoggerFactory.getLogger(CommandHandlers.class);
    private final EventStore eventStore;
    private final AccountProjector projector;

    public CommandHandlers(EventStore eventStore, AccountProjector projector) {
        this.eventStore = eventStore;
        this.projector = projector;
    }

    // ─── Create Account ──────────────────────────────────────────────────────

    public List<Map<String, Object>> handleCreateAccount(String accountId, String ownerName,
                                                          BigDecimal initialBalance, String currency)
            throws SQLException {
        if (accountId == null || accountId.isBlank() || ownerName == null || ownerName.isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "accountId and ownerName are required.");
        }

        return DatabaseManager.withTransaction(conn -> {
            // Check for duplicate aggregate
            if (aggregateExists(conn, accountId)) {
                throw new DomainException("CONFLICT", "Account '" + accountId + "' already exists.");
            }

            BankAccount account = BankAccount.createNew(
                    accountId, ownerName,
                    initialBalance != null ? initialBalance : BigDecimal.ZERO,
                    currency != null ? currency : "USD"
            );

            List<DomainEvent> pendingEvents = account.flushChanges();

            List<Map<String, Object>> savedEvents = eventStore.saveEvents(
                    conn, account.getAccountId(), "BankAccount", pendingEvents, 0
            );

            eventStore.maybeSnapshot(conn, account, 0, account.getVersion());
            projector.projectEvents(savedEvents, conn);

            log.info("Account created: {}", accountId);
            return savedEvents;
        });
    }

    // ─── Deposit ─────────────────────────────────────────────────────────────

    public List<Map<String, Object>> handleDeposit(String accountId, BigDecimal amount,
                                                    String description, String transactionId)
            throws SQLException {
        validateAmountAndTransactionId(amount, transactionId);

        return DatabaseManager.withTransaction(conn -> {
            BankAccount account = loadForWrite(accountId);
            int prevVersion = account.getVersion();

            account.deposit(amount, description, transactionId);
            List<DomainEvent> pendingEvents = account.flushChanges();

            List<Map<String, Object>> savedEvents = eventStore.saveEvents(
                    conn, accountId, "BankAccount", pendingEvents, prevVersion
            );

            eventStore.maybeSnapshot(conn, account, prevVersion, account.getVersion());
            projector.projectEvents(savedEvents, conn);

            log.info("Deposit of {} to {}", amount, accountId);
            return savedEvents;
        });
    }

    // ─── Withdraw ────────────────────────────────────────────────────────────

    public List<Map<String, Object>> handleWithdraw(String accountId, BigDecimal amount,
                                                     String description, String transactionId)
            throws SQLException {
        validateAmountAndTransactionId(amount, transactionId);

        return DatabaseManager.withTransaction(conn -> {
            BankAccount account = loadForWrite(accountId);
            int prevVersion = account.getVersion();

            account.withdraw(amount, description, transactionId);
            List<DomainEvent> pendingEvents = account.flushChanges();

            List<Map<String, Object>> savedEvents = eventStore.saveEvents(
                    conn, accountId, "BankAccount", pendingEvents, prevVersion
            );

            eventStore.maybeSnapshot(conn, account, prevVersion, account.getVersion());
            projector.projectEvents(savedEvents, conn);

            log.info("Withdrawal of {} from {}", amount, accountId);
            return savedEvents;
        });
    }

    // ─── Close Account ───────────────────────────────────────────────────────

    public List<Map<String, Object>> handleClose(String accountId, String reason)
            throws SQLException {

        return DatabaseManager.withTransaction(conn -> {
            BankAccount account = loadForWrite(accountId);
            int prevVersion = account.getVersion();

            account.close(reason != null ? reason : "No reason provided.");
            List<DomainEvent> pendingEvents = account.flushChanges();

            List<Map<String, Object>> savedEvents = eventStore.saveEvents(
                    conn, accountId, "BankAccount", pendingEvents, prevVersion
            );

            eventStore.maybeSnapshot(conn, account, prevVersion, account.getVersion());
            projector.projectEvents(savedEvents, conn);

            log.info("Account closed: {}", accountId);
            return savedEvents;
        });
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private BankAccount loadForWrite(String accountId) throws SQLException {
        BankAccount account = eventStore.loadAggregate(accountId);
        if (account == null) {
            throw new DomainException("NOT_FOUND", "Account '" + accountId + "' not found.");
        }
        return account;
    }

    private boolean aggregateExists(Connection conn, String accountId) throws SQLException {
        String sql = "SELECT event_id FROM events WHERE aggregate_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void validateAmountAndTransactionId(BigDecimal amount, String transactionId) {
        if (amount == null) {
            throw new DomainException("VALIDATION_ERROR", "amount is required and must be a number.");
        }
        if (transactionId == null || transactionId.isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "transactionId is required.");
        }
    }
}

package com.bank.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BankAccount Aggregate Root.
 * <p>
 * Reconstructed by replaying events (event sourcing) or from a snapshot + tail events.
 * All state mutations happen exclusively through event application — commands
 * only validate invariants and then produce events via {@link #applyAndRecord}.
 * </p>
 */
public class BankAccount {

    private String accountId;
    private String ownerName;
    private BigDecimal balance;
    private String currency;
    private AccountStatus status;
    private int version;                     // last applied event_number
    private final List<DomainEvent> changes; // uncommitted events

    // ─── Constructor ──────────────────────────────────────────────────────────

    public BankAccount() {
        this.balance = BigDecimal.ZERO;
        this.version = 0;
        this.changes = new ArrayList<>();
    }

    // ─── Factory: Create Brand-New Account ────────────────────────────────────

    public static BankAccount createNew(String accountId, String ownerName,
                                        BigDecimal initialBalance, String currency) {
        BankAccount account = new BankAccount();
        Map<String, Object> data = new HashMap<>();
        data.put("accountId", accountId);
        data.put("ownerName", ownerName);
        data.put("initialBalance", initialBalance);
        data.put("currency", currency != null ? currency : "USD");

        account.applyAndRecord(new DomainEvent("AccountCreated", data));
        return account;
    }

    // ─── Factory: Reconstitute from Full Event Stream ─────────────────────────

    @SuppressWarnings("unchecked")
    public static BankAccount fromEvents(List<Map<String, Object>> eventRows) {
        BankAccount account = new BankAccount();
        for (Map<String, Object> row : eventRows) {
            String eventType = (String) row.get("event_type");
            Object rawData = row.get("event_data");
            Map<String, Object> eventData = (rawData instanceof Map)
                    ? (Map<String, Object>) rawData
                    : new HashMap<>();
            int eventNumber = ((Number) row.get("event_number")).intValue();
            account.apply(eventType, eventData, eventNumber);
        }
        return account;
    }

    // ─── Factory: Reconstitute from Snapshot + Subsequent Events ──────────────

    @SuppressWarnings("unchecked")
    public static BankAccount fromSnapshot(Map<String, Object> snapshotData,
                                           List<Map<String, Object>> subsequentEvents) {
        BankAccount account = new BankAccount();
        account.accountId = (String) snapshotData.get("accountId");
        account.ownerName = (String) snapshotData.get("ownerName");
        account.balance = toBigDecimal(snapshotData.get("balance"));
        account.currency = (String) snapshotData.get("currency");
        account.status = AccountStatus.valueOf((String) snapshotData.get("status"));
        account.version = ((Number) snapshotData.get("version")).intValue();

        for (Map<String, Object> row : subsequentEvents) {
            String eventType = (String) row.get("event_type");
            Object rawData = row.get("event_data");
            Map<String, Object> eventData = (rawData instanceof Map)
                    ? (Map<String, Object>) rawData
                    : new HashMap<>();
            int eventNumber = ((Number) row.get("event_number")).intValue();
            account.apply(eventType, eventData, eventNumber);
        }
        return account;
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    public void deposit(BigDecimal amount, String description, String transactionId) {
        if (status != AccountStatus.OPEN) {
            throw new DomainException("ACCOUNT_CLOSED", "Account is closed.");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DomainException("INVALID_AMOUNT", "Deposit amount must be positive.");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("accountId", accountId);
        data.put("amount", amount);
        data.put("description", description);
        data.put("transactionId", transactionId);
        applyAndRecord(new DomainEvent("MoneyDeposited", data));
    }

    public void withdraw(BigDecimal amount, String description, String transactionId) {
        if (status != AccountStatus.OPEN) {
            throw new DomainException("ACCOUNT_CLOSED", "Account is closed.");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DomainException("INVALID_AMOUNT", "Withdrawal amount must be positive.");
        }
        if (balance.compareTo(amount) < 0) {
            throw new DomainException("INSUFFICIENT_FUNDS", "Insufficient funds.");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("accountId", accountId);
        data.put("amount", amount);
        data.put("description", description);
        data.put("transactionId", transactionId);
        applyAndRecord(new DomainEvent("MoneyWithdrawn", data));
    }

    public void close(String reason) {
        if (status != AccountStatus.OPEN) {
            throw new DomainException("ACCOUNT_CLOSED", "Account is already closed.");
        }
        if (balance.compareTo(BigDecimal.ZERO) != 0) {
            throw new DomainException("NON_ZERO_BALANCE", "Account balance must be zero before closing.");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("accountId", accountId);
        data.put("reason", reason);
        applyAndRecord(new DomainEvent("AccountClosed", data));
    }

    // ─── Internal Apply Logic ─────────────────────────────────────────────────

    private void applyAndRecord(DomainEvent event) {
        apply(event.eventType(), event.data(), version + 1);
        changes.add(event);
    }

    private void apply(String eventType, Map<String, Object> data, int eventNumber) {
        switch (eventType) {
            case "AccountCreated" -> {
                this.accountId = (String) data.get("accountId");
                this.ownerName = (String) data.get("ownerName");
                this.balance = toBigDecimal(data.get("initialBalance"));
                this.currency = data.get("currency") != null ? (String) data.get("currency") : "USD";
                this.status = AccountStatus.OPEN;
            }
            case "MoneyDeposited" -> {
                BigDecimal amount = toBigDecimal(data.get("amount"));
                this.balance = this.balance.add(amount).setScale(4, RoundingMode.HALF_UP);
            }
            case "MoneyWithdrawn" -> {
                BigDecimal amount = toBigDecimal(data.get("amount"));
                this.balance = this.balance.subtract(amount).setScale(4, RoundingMode.HALF_UP);
            }
            case "AccountClosed" -> {
                this.status = AccountStatus.CLOSED;
            }
            default -> { /* ignore unknown events */ }
        }
        this.version = eventNumber;
    }

    // ─── Snapshot Helpers ─────────────────────────────────────────────────────

    public Map<String, Object> toSnapshot() {
        Map<String, Object> snap = new HashMap<>();
        snap.put("accountId", accountId);
        snap.put("ownerName", ownerName);
        snap.put("balance", balance);
        snap.put("currency", currency);
        snap.put("status", status.name());
        snap.put("version", version);
        return snap;
    }

    public List<DomainEvent> flushChanges() {
        List<DomainEvent> copy = new ArrayList<>(changes);
        changes.clear();
        return copy;
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    public String getAccountId()     { return accountId; }
    public String getOwnerName()     { return ownerName; }
    public BigDecimal getBalance()   { return balance; }
    public String getCurrency()      { return currency; }
    public AccountStatus getStatus() { return status; }
    public int getVersion()          { return version; }

    // ─── Utility ──────────────────────────────────────────────────────────────

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(value.toString());
    }
}

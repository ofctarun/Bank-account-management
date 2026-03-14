'use strict';

/**
 * BankAccount Aggregate Root
 * Reconstructed by replaying events (or from a snapshot + subsequent events).
 */

const ACCOUNT_STATUS = {
  OPEN: 'OPEN',
  CLOSED: 'CLOSED',
};

class BankAccount {
  constructor() {
    this.accountId = null;
    this.ownerName = null;
    this.balance = 0;
    this.currency = 'USD';
    this.status = null;
    this.version = 0;       // last applied event_number
    this.changes = [];       // uncommitted events
  }

  // ─── Factory / Reconstitution ─────────────────────────────────────────────

  static createNew({ accountId, ownerName, initialBalance = 0, currency = 'USD' }) {
    const account = new BankAccount();
    const event = {
      eventType: 'AccountCreated',
      data: { accountId, ownerName, initialBalance, currency },
    };
    account._applyAndRecord(event);
    return account;
  }

  static fromEvents(events) {
    const account = new BankAccount();
    for (const event of events) {
      account._apply(event.event_type, event.event_data, event.event_number);
    }
    return account;
  }

  static fromSnapshot(snapshotData, subsequentEvents = []) {
    const account = new BankAccount();
    account.accountId = snapshotData.accountId;
    account.ownerName = snapshotData.ownerName;
    account.balance = parseFloat(snapshotData.balance);
    account.currency = snapshotData.currency;
    account.status = snapshotData.status;
    account.version = snapshotData.version;

    for (const event of subsequentEvents) {
      account._apply(event.event_type, event.event_data, event.event_number);
    }
    return account;
  }

  // ─── Commands ─────────────────────────────────────────────────────────────

  deposit({ amount, description, transactionId }) {
    if (this.status !== ACCOUNT_STATUS.OPEN) {
      throw { code: 'ACCOUNT_CLOSED', message: 'Account is closed.' };
    }
    if (!amount || amount <= 0) {
      throw { code: 'INVALID_AMOUNT', message: 'Deposit amount must be positive.' };
    }
    this._applyAndRecord({
      eventType: 'MoneyDeposited',
      data: { accountId: this.accountId, amount, description, transactionId },
    });
  }

  withdraw({ amount, description, transactionId }) {
    if (this.status !== ACCOUNT_STATUS.OPEN) {
      throw { code: 'ACCOUNT_CLOSED', message: 'Account is closed.' };
    }
    if (!amount || amount <= 0) {
      throw { code: 'INVALID_AMOUNT', message: 'Withdrawal amount must be positive.' };
    }
    if (this.balance < amount) {
      throw { code: 'INSUFFICIENT_FUNDS', message: 'Insufficient funds.' };
    }
    this._applyAndRecord({
      eventType: 'MoneyWithdrawn',
      data: { accountId: this.accountId, amount, description, transactionId },
    });
  }

  close({ reason }) {
    if (this.status !== ACCOUNT_STATUS.OPEN) {
      throw { code: 'ACCOUNT_CLOSED', message: 'Account is already closed.' };
    }
    if (this.balance !== 0) {
      throw { code: 'NON_ZERO_BALANCE', message: 'Account balance must be zero before closing.' };
    }
    this._applyAndRecord({
      eventType: 'AccountClosed',
      data: { accountId: this.accountId, reason },
    });
  }

  // ─── Internal Apply Logic ─────────────────────────────────────────────────

  _applyAndRecord(event) {
    this._apply(event.eventType, event.data, this.version + 1);
    this.changes.push(event);
  }

  _apply(eventType, data, eventNumber) {
    switch (eventType) {
      case 'AccountCreated':
        this.accountId = data.accountId;
        this.ownerName = data.ownerName;
        this.balance = parseFloat(data.initialBalance) || 0;
        this.currency = data.currency || 'USD';
        this.status = ACCOUNT_STATUS.OPEN;
        break;

      case 'MoneyDeposited':
        this.balance = parseFloat((this.balance + parseFloat(data.amount)).toFixed(4));
        break;

      case 'MoneyWithdrawn':
        this.balance = parseFloat((this.balance - parseFloat(data.amount)).toFixed(4));
        break;

      case 'AccountClosed':
        this.status = ACCOUNT_STATUS.CLOSED;
        break;

      default:
        break;
    }
    this.version = eventNumber;
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  toSnapshot() {
    return {
      accountId: this.accountId,
      ownerName: this.ownerName,
      balance: this.balance,
      currency: this.currency,
      status: this.status,
      version: this.version,
    };
  }

  flushChanges() {
    const changes = [...this.changes];
    this.changes = [];
    return changes;
  }
}

module.exports = { BankAccount, ACCOUNT_STATUS };

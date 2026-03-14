'use strict';

const db = require('../db');
const { BankAccount } = require('../domain/BankAccount');
const eventStore = require('../eventStore');
const { projectEvents } = require('../projections/projector');

// ─── Create Account ───────────────────────────────────────────────────────────

async function handleCreateAccount({ accountId, ownerName, initialBalance = 0, currency = 'USD' }) {
  if (!accountId || !ownerName) {
    throw { code: 'VALIDATION_ERROR', message: 'accountId and ownerName are required.' };
  }

  return db.withTransaction(async (client) => {
    // Check for duplicate
    const existing = await client.query(
      `SELECT event_id FROM events WHERE aggregate_id = $1 LIMIT 1`,
      [accountId]
    );
    if (existing.rows.length > 0) {
      throw { code: 'CONFLICT', message: `Account '${accountId}' already exists.` };
    }

    const account = BankAccount.createNew({ accountId, ownerName, initialBalance, currency });
    const pendingEvents = account.flushChanges();

    const savedEvents = await saveEventsInTx(client, account.accountId, 'BankAccount', pendingEvents, 0);

    // Auto-snapshot check
    const newVersion = account.version;
    await maybeSnapshot(client, account, 0, newVersion);

    await projectEvents(savedEvents, client);
    return savedEvents;
  });
}

// ─── Deposit ──────────────────────────────────────────────────────────────────

async function handleDeposit(accountId, { amount, description, transactionId }) {
  if (!amount || isNaN(amount)) {
    throw { code: 'VALIDATION_ERROR', message: 'amount is required and must be a number.' };
  }
  if (!transactionId) {
    throw { code: 'VALIDATION_ERROR', message: 'transactionId is required.' };
  }

  return db.withTransaction(async (client) => {
    const account = await loadForWrite(accountId);

    const prevVersion = account.version;
    account.deposit({ amount: parseFloat(amount), description, transactionId });
    const pendingEvents = account.flushChanges();

    const savedEvents = await saveEventsInTx(client, accountId, 'BankAccount', pendingEvents, prevVersion);
    await maybeSnapshot(client, account, prevVersion, account.version);
    await projectEvents(savedEvents, client);
    return savedEvents;
  });
}

// ─── Withdraw ─────────────────────────────────────────────────────────────────

async function handleWithdraw(accountId, { amount, description, transactionId }) {
  if (!amount || isNaN(amount)) {
    throw { code: 'VALIDATION_ERROR', message: 'amount is required and must be a number.' };
  }
  if (!transactionId) {
    throw { code: 'VALIDATION_ERROR', message: 'transactionId is required.' };
  }

  return db.withTransaction(async (client) => {
    const account = await loadForWrite(accountId);

    const prevVersion = account.version;
    account.withdraw({ amount: parseFloat(amount), description, transactionId });
    const pendingEvents = account.flushChanges();

    const savedEvents = await saveEventsInTx(client, accountId, 'BankAccount', pendingEvents, prevVersion);
    await maybeSnapshot(client, account, prevVersion, account.version);
    await projectEvents(savedEvents, client);
    return savedEvents;
  });
}

// ─── Close Account ────────────────────────────────────────────────────────────

async function handleClose(accountId, { reason }) {
  return db.withTransaction(async (client) => {
    const account = await loadForWrite(accountId);

    const prevVersion = account.version;
    account.close({ reason: reason || 'No reason provided.' });
    const pendingEvents = account.flushChanges();

    const savedEvents = await saveEventsInTx(client, accountId, 'BankAccount', pendingEvents, prevVersion);
    await maybeSnapshot(client, account, prevVersion, account.version);
    await projectEvents(savedEvents, client);
    return savedEvents;
  });
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

async function loadForWrite(accountId) {
  const account = await eventStore.loadAggregate(accountId);
  if (!account) {
    throw { code: 'NOT_FOUND', message: `Account '${accountId}' not found.` };
  }
  return account;
}

const { v4: uuidv4 } = require('uuid');
const SNAPSHOT_THRESHOLD = 50;

async function saveEventsInTx(client, aggregateId, aggregateType, pendingEvents, currentVersion) {
  const savedEvents = [];
  let eventNumber = currentVersion + 1;

  for (const event of pendingEvents) {
    const eventId = uuidv4();
    const result = await client.query(
      `INSERT INTO events
         (event_id, aggregate_id, aggregate_type, event_type, event_data, event_number, version, global_sequence)
       VALUES ($1, $2, $3, $4, $5, $6, 1, nextval('events_global_seq'))
       RETURNING *`,
      [eventId, aggregateId, aggregateType, event.eventType, JSON.stringify(event.data), eventNumber]
    );
    savedEvents.push(result.rows[0]);
    eventNumber++;
  }

  return savedEvents;
}

async function maybeSnapshot(client, account, prevVersion, newVersion) {
  const THRESHOLD = SNAPSHOT_THRESHOLD;
  if (Math.floor(newVersion / THRESHOLD) > Math.floor(prevVersion / THRESHOLD)) {
    await eventStore.saveSnapshot(account.accountId, account.toSnapshot(), newVersion, client);
  }
}

module.exports = { handleCreateAccount, handleDeposit, handleWithdraw, handleClose };

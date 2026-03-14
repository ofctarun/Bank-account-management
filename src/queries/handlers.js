'use strict';

const db = require('../db');
const eventStore = require('../eventStore');
const { BankAccount } = require('../domain/BankAccount');

// ─── GET /api/accounts/:accountId ────────────────────────────────────────────

async function getAccount(accountId) {
  const result = await db.query(
    `SELECT account_id, owner_name, balance, currency, status
     FROM account_summaries
     WHERE account_id = $1`,
    [accountId]
  );
  if (result.rows.length === 0) return null;
  const r = result.rows[0];
  return {
    accountId: r.account_id,
    ownerName: r.owner_name,
    balance: parseFloat(r.balance),
    currency: r.currency,
    status: r.status,
  };
}

// ─── GET /api/accounts/:accountId/events ─────────────────────────────────────

async function getEventStream(accountId) {
  const result = await db.query(
    `SELECT event_id, event_type, event_number, event_data, timestamp
     FROM events
     WHERE aggregate_id = $1
     ORDER BY event_number ASC`,
    [accountId]
  );
  return result.rows.map((r) => ({
    eventId: r.event_id,
    eventType: r.event_type,
    eventNumber: r.event_number,
    data: r.event_data,
    timestamp: r.timestamp,
  }));
}

// ─── GET /api/accounts/:accountId/balance-at/:timestamp ──────────────────────

async function getBalanceAt(accountId, timestamp) {
  // Check account exists at all
  const checkRes = await db.query(
    `SELECT COUNT(*) as count FROM events WHERE aggregate_id = $1`,
    [accountId]
  );
  if (parseInt(checkRes.rows[0].count, 10) === 0) return null;

  // Load events up to the timestamp
  const events = await eventStore.loadEventsBefore(accountId, timestamp);
  if (events.length === 0) {
    return { accountId, balanceAt: 0, timestamp };
  }

  const account = BankAccount.fromEvents(events);
  return {
    accountId,
    balanceAt: account.balance,
    timestamp,
  };
}

// ─── GET /api/accounts/:accountId/transactions ───────────────────────────────

async function getTransactions(accountId, page = 1, pageSize = 10) {
  const offset = (page - 1) * pageSize;

  const countRes = await db.query(
    `SELECT COUNT(*) as count FROM transaction_history WHERE account_id = $1`,
    [accountId]
  );
  const totalCount = parseInt(countRes.rows[0].count, 10);
  const totalPages = Math.ceil(totalCount / pageSize) || 1;

  const dataRes = await db.query(
    `SELECT transaction_id, type, amount, description, timestamp
     FROM transaction_history
     WHERE account_id = $1
     ORDER BY timestamp DESC
     LIMIT $2 OFFSET $3`,
    [accountId, pageSize, offset]
  );

  return {
    currentPage: page,
    pageSize,
    totalPages,
    totalCount,
    items: dataRes.rows.map((r) => ({
      transactionId: r.transaction_id,
      type: r.type,
      amount: parseFloat(r.amount),
      description: r.description,
      timestamp: r.timestamp,
    })),
  };
}

module.exports = { getAccount, getEventStream, getBalanceAt, getTransactions };

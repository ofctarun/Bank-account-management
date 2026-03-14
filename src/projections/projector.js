'use strict';

const db = require('../db');

// ─── Project a batch of saved event rows into read models ────────────────────

async function projectEvents(savedEventRows, client) {
  for (const row of savedEventRows) {
    await applyToAccountSummaries(row, client);
    await applyToTransactionHistory(row, client);
    await updateCheckpoint(row, client);
  }
}

// ─── AccountSummaries Projection ─────────────────────────────────────────────

async function applyToAccountSummaries(eventRow, client) {
  const data = eventRow.event_data;

  switch (eventRow.event_type) {
    case 'AccountCreated':
      await client.query(
        `INSERT INTO account_summaries (account_id, owner_name, balance, currency, status, version)
         VALUES ($1, $2, $3, $4, 'OPEN', $5)
         ON CONFLICT (account_id) DO UPDATE SET
           owner_name = EXCLUDED.owner_name,
           balance    = EXCLUDED.balance,
           currency   = EXCLUDED.currency,
           status     = EXCLUDED.status,
           version    = EXCLUDED.version`,
        [data.accountId, data.ownerName, parseFloat(data.initialBalance) || 0, data.currency, eventRow.global_sequence]
      );
      break;

    case 'MoneyDeposited':
      await client.query(
        `UPDATE account_summaries
         SET balance = balance + $2, version = $3
         WHERE account_id = $1`,
        [data.accountId, parseFloat(data.amount), eventRow.global_sequence]
      );
      break;

    case 'MoneyWithdrawn':
      await client.query(
        `UPDATE account_summaries
         SET balance = balance - $2, version = $3
         WHERE account_id = $1`,
        [data.accountId, parseFloat(data.amount), eventRow.global_sequence]
      );
      break;

    case 'AccountClosed':
      await client.query(
        `UPDATE account_summaries
         SET status = 'CLOSED', version = $2
         WHERE account_id = $1`,
        [data.accountId, eventRow.global_sequence]
      );
      break;

    default:
      break;
  }
}

// ─── TransactionHistory Projection ───────────────────────────────────────────

async function applyToTransactionHistory(eventRow, client) {
  const data = eventRow.event_data;

  switch (eventRow.event_type) {
    case 'MoneyDeposited':
      await client.query(
        `INSERT INTO transaction_history (transaction_id, account_id, type, amount, description, timestamp)
         VALUES ($1, $2, 'DEPOSIT', $3, $4, $5)
         ON CONFLICT (transaction_id) DO NOTHING`,
        [data.transactionId, data.accountId, parseFloat(data.amount), data.description || null, eventRow.timestamp]
      );
      break;

    case 'MoneyWithdrawn':
      await client.query(
        `INSERT INTO transaction_history (transaction_id, account_id, type, amount, description, timestamp)
         VALUES ($1, $2, 'WITHDRAWAL', $3, $4, $5)
         ON CONFLICT (transaction_id) DO NOTHING`,
        [data.transactionId, data.accountId, parseFloat(data.amount), data.description || null, eventRow.timestamp]
      );
      break;

    default:
      break;
  }
}

// ─── Checkpoint Management ────────────────────────────────────────────────────

async function updateCheckpoint(eventRow, client) {
  const seq = eventRow.global_sequence;
  await client.query(
    `UPDATE projection_checkpoints
     SET last_processed_event_id = GREATEST(last_processed_event_id, $1), updated_at = NOW()
     WHERE projection_name IN ('AccountSummaries', 'TransactionHistory')`,
    [seq]
  );
}

// ─── Full Rebuild ─────────────────────────────────────────────────────────────

async function rebuildAllProjections() {
  const dbModule = db;

  return dbModule.withTransaction(async (client) => {
    // Clear read models
    await client.query('DELETE FROM account_summaries');
    await client.query('DELETE FROM transaction_history');
    await client.query(
      `UPDATE projection_checkpoints SET last_processed_event_id = 0, updated_at = NOW()`
    );

    // Replay all events in global order
    const { rows } = await client.query(
      `SELECT * FROM events ORDER BY global_sequence ASC`
    );

    for (const row of rows) {
      await applyToAccountSummaries(row, client);
      await applyToTransactionHistory(row, client);
      await updateCheckpoint(row, client);
    }

    return rows.length;
  });
}

// ─── Projection Status ────────────────────────────────────────────────────────

async function getProjectionStatus() {
  const totalRes = await db.query(`SELECT COUNT(*) as count FROM events`);
  const totalEvents = parseInt(totalRes.rows[0].count, 10);

  const maxSeqRes = await db.query(`SELECT MAX(global_sequence) as max_seq FROM events`);
  const maxSeq = parseInt(maxSeqRes.rows[0].max_seq, 10) || 0;

  const checkRes = await db.query(`SELECT * FROM projection_checkpoints`);
  const projections = checkRes.rows.map((row) => ({
    name: row.projection_name,
    lastProcessedEventNumberGlobal: parseInt(row.last_processed_event_id, 10),
    lag: Math.max(0, maxSeq - parseInt(row.last_processed_event_id, 10)),
  }));

  return { totalEventsInStore: totalEvents, projections };
}

module.exports = { projectEvents, rebuildAllProjections, getProjectionStatus };

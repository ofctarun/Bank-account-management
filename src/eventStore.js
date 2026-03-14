'use strict';

const { v4: uuidv4 } = require('uuid');
const db = require('./db');
const { BankAccount } = require('./domain/BankAccount');

const SNAPSHOT_THRESHOLD = 50;

// ─── Load Aggregate ──────────────────────────────────────────────────────────

async function loadAggregate(aggregateId) {
  const snapshot = await loadSnapshot(aggregateId);

  let fromEventNumber = 0;
  let account;

  if (snapshot) {
    fromEventNumber = snapshot.last_event_number + 1;
    // Load only events after the snapshot
    const events = await loadEvents(aggregateId, fromEventNumber);
    account = BankAccount.fromSnapshot(snapshot.snapshot_data, events);
  } else {
    const events = await loadEvents(aggregateId);
    if (events.length === 0) return null;
    account = BankAccount.fromEvents(events);
  }

  return account;
}

// ─── Load Events ─────────────────────────────────────────────────────────────

async function loadEvents(aggregateId, fromEventNumber = 1) {
  const result = await db.query(
    `SELECT event_id, aggregate_id, event_type, event_data, event_number, timestamp, version
     FROM events
     WHERE aggregate_id = $1 AND event_number >= $2
     ORDER BY event_number ASC`,
    [aggregateId, fromEventNumber]
  );
  return result.rows;
}

async function loadAllEvents(aggregateId) {
  const result = await db.query(
    `SELECT event_id, aggregate_id, event_type, event_data, event_number, timestamp, version, global_sequence
     FROM events
     WHERE aggregate_id = $1
     ORDER BY event_number ASC`,
    [aggregateId]
  );
  return result.rows;
}

async function loadEventsBefore(aggregateId, timestamp) {
  const result = await db.query(
    `SELECT event_id, aggregate_id, event_type, event_data, event_number, timestamp, version
     FROM events
     WHERE aggregate_id = $1 AND timestamp <= $2
     ORDER BY event_number ASC`,
    [aggregateId, timestamp]
  );
  return result.rows;
}

// ─── Save Events ─────────────────────────────────────────────────────────────

async function saveEvents(aggregateId, aggregateType, pendingEvents, currentVersion, client) {
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

// ─── Snapshot ────────────────────────────────────────────────────────────────

async function loadSnapshot(aggregateId) {
  const result = await db.query(
    `SELECT * FROM snapshots WHERE aggregate_id = $1`,
    [aggregateId]
  );
  return result.rows[0] || null;
}

async function saveSnapshot(aggregateId, snapshotData, lastEventNumber, client) {
  await client.query(
    `INSERT INTO snapshots (snapshot_id, aggregate_id, snapshot_data, last_event_number, created_at)
     VALUES ($1, $2, $3, $4, NOW())
     ON CONFLICT (aggregate_id)
     DO UPDATE SET snapshot_data = EXCLUDED.snapshot_data,
                   last_event_number = EXCLUDED.last_event_number,
                   created_at = NOW()`,
    [uuidv4(), aggregateId, JSON.stringify(snapshotData), lastEventNumber]
  );
}

// ─── Save Aggregate (full pipeline) ──────────────────────────────────────────

async function saveAggregate(account, aggregateType = 'BankAccount') {
  const pendingEvents = account.flushChanges();
  if (pendingEvents.length === 0) return [];

  const currentVersion = account.version - pendingEvents.length;

  return db.withTransaction(async (client) => {
    const savedEvents = await saveEvents(
      account.accountId,
      aggregateType,
      pendingEvents,
      currentVersion,
      client
    );

    const newVersion = account.version;

    // Auto-snapshot after every SNAPSHOT_THRESHOLD events
    if (Math.floor(newVersion / SNAPSHOT_THRESHOLD) > Math.floor(currentVersion / SNAPSHOT_THRESHOLD)) {
      await saveSnapshot(account.accountId, account.toSnapshot(), newVersion, client);
    }

    return savedEvents;
  });
}

// ─── Count Events ─────────────────────────────────────────────────────────────

async function getTotalEventCount() {
  const result = await db.query(`SELECT COUNT(*) as count FROM events`);
  return parseInt(result.rows[0].count, 10);
}

async function getMaxGlobalSequence() {
  const result = await db.query(`SELECT MAX(global_sequence) as max_seq FROM events`);
  return parseInt(result.rows[0].max_seq, 10) || 0;
}

module.exports = {
  loadAggregate,
  loadEvents,
  loadAllEvents,
  loadEventsBefore,
  saveAggregate,
  loadSnapshot,
  saveSnapshot,
  getTotalEventCount,
  getMaxGlobalSequence,
};

-- ============================================================
-- Bank Account Management System - Database Schema
-- ============================================================

-- 1. EVENT STORE TABLE
CREATE TABLE IF NOT EXISTS events (
    event_id        UUID                        PRIMARY KEY NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id    VARCHAR(255)                NOT NULL,
    aggregate_type  VARCHAR(255)                NOT NULL,
    event_type      VARCHAR(255)                NOT NULL,
    event_data      JSONB                       NOT NULL,
    event_number    INTEGER                     NOT NULL,
    timestamp       TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    version         INTEGER                     NOT NULL DEFAULT 1,
    CONSTRAINT uq_aggregate_event UNIQUE (aggregate_id, event_number)
);

CREATE INDEX IF NOT EXISTS idx_events_aggregate_id ON events (aggregate_id);
CREATE INDEX IF NOT EXISTS idx_events_timestamp ON events (timestamp);

-- 2. SNAPSHOTS TABLE
CREATE TABLE IF NOT EXISTS snapshots (
    snapshot_id         UUID            PRIMARY KEY NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id        VARCHAR(255)    NOT NULL UNIQUE,
    snapshot_data       JSONB           NOT NULL,
    last_event_number   INTEGER         NOT NULL,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_snapshots_aggregate_id ON snapshots (aggregate_id);

-- 3. READ MODEL: ACCOUNT SUMMARIES
CREATE TABLE IF NOT EXISTS account_summaries (
    account_id      VARCHAR(255)        PRIMARY KEY NOT NULL,
    owner_name      VARCHAR(255)        NOT NULL,
    balance         DECIMAL(19, 4)      NOT NULL,
    currency        VARCHAR(3)          NOT NULL,
    status          VARCHAR(50)         NOT NULL DEFAULT 'OPEN',
    version         BIGINT              NOT NULL DEFAULT 0
);

-- 4. READ MODEL: TRANSACTION HISTORY
CREATE TABLE IF NOT EXISTS transaction_history (
    transaction_id  VARCHAR(255)                PRIMARY KEY NOT NULL,
    account_id      VARCHAR(255)                NOT NULL,
    type            VARCHAR(50)                 NOT NULL,
    amount          DECIMAL(19, 4)              NOT NULL,
    description     TEXT,
    timestamp       TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tx_account_id ON transaction_history (account_id);
CREATE INDEX IF NOT EXISTS idx_tx_timestamp ON transaction_history (timestamp);

-- 5. PROJECTION CHECKPOINTS (tracks per-projection last processed global sequence)
CREATE TABLE IF NOT EXISTS projection_checkpoints (
    projection_name         VARCHAR(255)    PRIMARY KEY NOT NULL,
    last_processed_event_id BIGINT          NOT NULL DEFAULT 0,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Seed initial checkpoint rows
INSERT INTO projection_checkpoints (projection_name, last_processed_event_id)
VALUES
    ('AccountSummaries', 0),
    ('TransactionHistory', 0)
ON CONFLICT (projection_name) DO NOTHING;

-- Global sequence for ordering events across all aggregates
CREATE SEQUENCE IF NOT EXISTS events_global_seq;

ALTER TABLE events
    ADD COLUMN IF NOT EXISTS global_sequence BIGINT DEFAULT nextval('events_global_seq');

CREATE INDEX IF NOT EXISTS idx_events_global_seq ON events (global_sequence);

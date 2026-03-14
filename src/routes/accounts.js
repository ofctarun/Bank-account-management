'use strict';

const express = require('express');
const router = express.Router();

const { handleCreateAccount, handleDeposit, handleWithdraw, handleClose } = require('../commands/handlers');
const { getAccount, getEventStream, getBalanceAt, getTransactions } = require('../queries/handlers');

// ─── Error mapper ─────────────────────────────────────────────────────────────

function mapError(err, res) {
  const code = err.code || 'INTERNAL';
  if (code === 'VALIDATION_ERROR') return res.status(400).json({ error: err.message });
  if (code === 'NOT_FOUND') return res.status(404).json({ error: err.message });
  if (code === 'CONFLICT') return res.status(409).json({ error: err.message });
  if (code === 'ACCOUNT_CLOSED') return res.status(409).json({ error: err.message });
  if (code === 'INSUFFICIENT_FUNDS') return res.status(409).json({ error: err.message });
  if (code === 'NON_ZERO_BALANCE') return res.status(409).json({ error: err.message });
  if (code === 'INVALID_AMOUNT') return res.status(400).json({ error: err.message });
  console.error('Unhandled error:', err);
  return res.status(500).json({ error: 'Internal server error.' });
}

// ─── POST /api/accounts ───────────────────────────────────────────────────────

router.post('/', async (req, res) => {
  try {
    const { accountId, ownerName, initialBalance, currency } = req.body;
    if (!accountId || !ownerName) {
      return res.status(400).json({ error: 'accountId and ownerName are required.' });
    }
    await handleCreateAccount({ accountId, ownerName, initialBalance, currency });
    return res.status(202).json({ message: 'Account creation accepted.', accountId });
  } catch (err) {
    return mapError(err, res);
  }
});

// ─── POST /api/accounts/:accountId/deposit ────────────────────────────────────

router.post('/:accountId/deposit', async (req, res) => {
  try {
    const { accountId } = req.params;
    const { amount, description, transactionId } = req.body;
    if (!amount) return res.status(400).json({ error: 'amount is required.' });
    if (!transactionId) return res.status(400).json({ error: 'transactionId is required.' });
    await handleDeposit(accountId, { amount, description, transactionId });
    return res.status(202).json({ message: 'Deposit accepted.', accountId });
  } catch (err) {
    return mapError(err, res);
  }
});

// ─── POST /api/accounts/:accountId/withdraw ───────────────────────────────────

router.post('/:accountId/withdraw', async (req, res) => {
  try {
    const { accountId } = req.params;
    const { amount, description, transactionId } = req.body;
    if (!amount) return res.status(400).json({ error: 'amount is required.' });
    if (!transactionId) return res.status(400).json({ error: 'transactionId is required.' });
    await handleWithdraw(accountId, { amount, description, transactionId });
    return res.status(202).json({ message: 'Withdrawal accepted.', accountId });
  } catch (err) {
    return mapError(err, res);
  }
});

// ─── POST /api/accounts/:accountId/close ─────────────────────────────────────

router.post('/:accountId/close', async (req, res) => {
  try {
    const { accountId } = req.params;
    const { reason } = req.body;
    await handleClose(accountId, { reason });
    return res.status(202).json({ message: 'Account close accepted.', accountId });
  } catch (err) {
    return mapError(err, res);
  }
});

// ─── GET /api/accounts/:accountId ────────────────────────────────────────────

router.get('/:accountId', async (req, res) => {
  try {
    const { accountId } = req.params;
    const account = await getAccount(accountId);
    if (!account) return res.status(404).json({ error: `Account '${accountId}' not found.` });
    return res.status(200).json(account);
  } catch (err) {
    return mapError(err, res);
  }
});

// ─── GET /api/accounts/:accountId/events ─────────────────────────────────────

router.get('/:accountId/events', async (req, res) => {
  try {
    const { accountId } = req.params;
    const events = await getEventStream(accountId);
    if (events.length === 0) return res.status(404).json({ error: `No events found for account '${accountId}'.` });
    return res.status(200).json(events);
  } catch (err) {
    return mapError(err, res);
  }
});

// ─── GET /api/accounts/:accountId/balance-at/:timestamp ──────────────────────

router.get('/:accountId/balance-at/:timestamp', async (req, res) => {
  try {
    const { accountId, timestamp } = req.params;
    const decodedTs = decodeURIComponent(timestamp);
    const result = await getBalanceAt(accountId, decodedTs);
    if (!result) return res.status(404).json({ error: `Account '${accountId}' not found.` });
    return res.status(200).json(result);
  } catch (err) {
    return mapError(err, res);
  }
});

// ─── GET /api/accounts/:accountId/transactions ───────────────────────────────

router.get('/:accountId/transactions', async (req, res) => {
  try {
    const { accountId } = req.params;
    const page = Math.max(1, parseInt(req.query.page) || 1);
    const pageSize = Math.max(1, parseInt(req.query.pageSize) || 10);
    const result = await getTransactions(accountId, page, pageSize);
    return res.status(200).json(result);
  } catch (err) {
    return mapError(err, res);
  }
});

module.exports = router;

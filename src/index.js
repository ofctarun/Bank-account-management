'use strict';

require('dotenv').config();
const express = require('express');

const accountRoutes = require('./routes/accounts');
const projectionRoutes = require('./routes/projections');

const app = express();
const PORT = process.env.API_PORT || 8080;

// ─── Middleware ───────────────────────────────────────────────────────────────

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Request logger
app.use((req, _res, next) => {
  console.log(`[${new Date().toISOString()}] ${req.method} ${req.url}`);
  next();
});

// ─── Health Check ─────────────────────────────────────────────────────────────

app.get('/health', (_req, res) => {
  res.status(200).json({ status: 'ok', timestamp: new Date().toISOString() });
});

// ─── Routes ──────────────────────────────────────────────────────────────────

app.use('/api/accounts', accountRoutes);
app.use('/api/projections', projectionRoutes);

// ─── 404 Fallback ─────────────────────────────────────────────────────────────

app.use((_req, res) => {
  res.status(404).json({ error: 'Not found.' });
});

// ─── Global Error Handler ─────────────────────────────────────────────────────

app.use((err, _req, res, _next) => {
  console.error('Unhandled error:', err);
  res.status(500).json({ error: 'Internal server error.' });
});

// ─── Start Server ─────────────────────────────────────────────────────────────

app.listen(PORT, '0.0.0.0', () => {
  console.log(`Bank Account Management API listening on port ${PORT}`);
});

module.exports = app;

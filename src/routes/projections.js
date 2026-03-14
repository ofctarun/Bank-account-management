'use strict';

const express = require('express');
const router = express.Router();
const { rebuildAllProjections, getProjectionStatus } = require('../projections/projector');

// ─── POST /api/projections/rebuild ───────────────────────────────────────────

router.post('/rebuild', async (req, res) => {
  try {
    // Kick off rebuild asynchronously (fire and forget for large datasets)
    rebuildAllProjections()
      .then((count) => console.log(`Projection rebuild complete. Processed ${count} events.`))
      .catch((err) => console.error('Projection rebuild error:', err));

    return res.status(202).json({ message: 'Projection rebuild initiated.' });
  } catch (err) {
    console.error('Rebuild error:', err);
    return res.status(500).json({ error: 'Failed to initiate rebuild.' });
  }
});

// ─── GET /api/projections/status ─────────────────────────────────────────────

router.get('/status', async (req, res) => {
  try {
    const status = await getProjectionStatus();
    return res.status(200).json(status);
  } catch (err) {
    console.error('Status error:', err);
    return res.status(500).json({ error: 'Failed to get projection status.' });
  }
});

module.exports = router;

# Bank Account Management System

A fully containerized bank account management API implementing **Event Sourcing** and **CQRS** patterns using Node.js, Express, and PostgreSQL.

## Quick Start

```bash
# Clone & start
git clone <repo-url>
cd Bank-account-management
cp .env.example .env
docker-compose up --build
```

The API will be available at `http://localhost:8080`.

## Architecture

- **Event Sourcing**: All state changes are stored as immutable events in the `events` table
- **CQRS**: Write side uses command handlers + aggregates; Read side uses projections
- **Snapshots**: Automatically created every 50 events for performance
- **Time-travel**: Reconstruct account state at any historical timestamp

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/accounts` | Create a new account |
| `POST` | `/api/accounts/:id/deposit` | Deposit money |
| `POST` | `/api/accounts/:id/withdraw` | Withdraw money |
| `POST` | `/api/accounts/:id/close` | Close account |
| `GET` | `/api/accounts/:id` | Get account state |
| `GET` | `/api/accounts/:id/events` | Get event stream |
| `GET` | `/api/accounts/:id/balance-at/:ts` | Time-travel balance query |
| `GET` | `/api/accounts/:id/transactions` | Paginated transaction history |
| `POST` | `/api/projections/rebuild` | Rebuild all projections |
| `GET` | `/api/projections/status` | Projection lag status |
| `GET` | `/health` | Health check |

## Environment Variables

See `.env.example` for all required variables.

## Database Schema

- `events` — Append-only event store
- `snapshots` — Periodic aggregate snapshots (every 50 events)
- `account_summaries` — Read model for account state
- `transaction_history` — Read model for transactions
- `projection_checkpoints` — Tracks projection processing position

# FinTrace

> Event Sourcing & CQRS — Pure Java 21 (No Spring Boot)

A fully event-sourced bank account management API built with **raw Java engineering**:
no Spring Boot, no Hibernate, no DI frameworks.

## Tech Stack

| Layer | Technology | Why |
|-------|-----------|-----|
| Web Framework | **Javalin 6** | Express.js-like, lightweight |
| Database | **PostgreSQL 16** + **JDBC** | Direct SQL, zero ORM |
| Connection Pool | **HikariCP** | High-performance, explicit lifecycle |
| JSON | **Jackson** | Manual serialization |
| Concurrency | **Java 21 Virtual Threads** | Async projection rebuilds |
| Frontend | **React** + **Vite** | Modern SPA dashboard |
| Build | **Maven** + Shade plugin | Fat JAR packaging |
| Container | **Docker** multi-stage build | Alpine JRE-21 runtime |

## Architecture

```
┌──────────────┐    ┌──────────────────┐    ┌───────────────┐
│   React UI   │───▶│   API Layer      │───▶│  BankAccount  │
│  (Vite SPA)  │    │   (Javalin)      │    │  (Aggregate)  │
└──────────────┘    └────────┬─────────┘    └───────────────┘
                             │
                    ┌────────┴─────────┐
                    │   Event Store    │
                    │   (Raw JDBC)     │
                    └────────┬─────────┘
                             │
                    ┌────────┴─────────┐
                    │   Projector      │
                    │ (Virtual Threads)│
                    └──────────────────┘
```

## Project Structure

```
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── db/init.sql
├── frontend/                         # React dashboard (Vite)
└── src/main/java/com/bank/
    ├── App.java                      # Entry point, composition root
    ├── domain/
    │   ├── BankAccount.java          # Aggregate root (event-sourced)
    │   ├── AccountStatus.java
    │   ├── DomainEvent.java          # Immutable record
    │   └── DomainException.java
    ├── infrastructure/
    │   ├── DatabaseManager.java      # HikariCP + transactions
    │   └── EventStore.java           # Raw JDBC persistence
    ├── commands/CommandHandlers.java  # Write-side CQRS
    ├── queries/QueryHandlers.java    # Read-side CQRS
    ├── projections/AccountProjector.java
    └── routes/ApiRouter.java
```

## Quick Start

```bash
# Backend (Java + PostgreSQL)
docker compose up --build

# Frontend (React)
cd frontend && npm install && npm run dev
```

- **Backend API**: http://localhost:8080
- **Frontend UI**: http://localhost:5173

## API Endpoints

### Commands (Write)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/accounts` | Create account |
| POST | `/api/accounts/:id/deposit` | Deposit funds |
| POST | `/api/accounts/:id/withdraw` | Withdraw funds |
| POST | `/api/accounts/:id/close` | Close account |

### Queries (Read)
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/accounts/:id` | Account summary |
| GET | `/api/accounts/:id/events` | Event stream |
| GET | `/api/accounts/:id/balance-at/:ts` | Time-travel balance |
| GET | `/api/accounts/:id/transactions` | Paginated transactions |

### Projections
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/projections/rebuild` | Rebuild all projections |
| GET | `/api/projections/status` | Projection lag status |

## Key Engineering Highlights

- **Event Sourcing**: All state changes stored as immutable events
- **CQRS**: Strict separation of write/read models
- **Raw JDBC**: No ORM — explicit SQL with prepared statements
- **Virtual Threads (Project Loom)**: Async projection rebuilds
- **BigDecimal**: Financial-grade precision
- **Snapshotting**: Auto-snapshot every 50 events
- **Composition Root**: Explicit DI wiring — no framework

## Environment Variables

```env
API_PORT=8080
DATABASE_URL=postgresql://bank_user:bank_password@db:5432/bank_db
DB_USER=bank_user
DB_PASSWORD=bank_password
DB_NAME=bank_db
```

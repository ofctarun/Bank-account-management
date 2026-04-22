package com.bank.routes;

import com.bank.commands.CommandHandlers;
import com.bank.domain.DomainException;
import com.bank.projections.AccountProjector;
import com.bank.queries.QueryHandlers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * REST API Router — maps HTTP endpoints to command/query handlers.
 * <p>
 * Uses Javalin (Express.js-like) for lightweight routing.
 * All error mapping is centralized via {@link #mapError}.
 * </p>
 */
public class ApiRouter {

    private static final Logger log = LoggerFactory.getLogger(ApiRouter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final CommandHandlers commandHandlers;
    private final QueryHandlers queryHandlers;
    private final AccountProjector projector;

    public ApiRouter(CommandHandlers commandHandlers, QueryHandlers queryHandlers,
                     AccountProjector projector) {
        this.commandHandlers = commandHandlers;
        this.queryHandlers = queryHandlers;
        this.projector = projector;
    }

    /**
     * Register all routes on the Javalin app.
     */
    public void registerRoutes(Javalin app) {

        // ─── Health Check ────────────────────────────────────────────────
        app.get("/health", ctx -> {
            ctx.status(200).json(Map.of(
                    "status", "ok",
                    "timestamp", java.time.Instant.now().toString()
            ));
        });

        // ─── ACCOUNT COMMANDS (Write Side) ───────────────────────────────

        // POST /api/accounts
        app.post("/api/accounts", ctx -> {
            try {
                Map<String, Object> body = parseBody(ctx);
                String accountId = (String) body.get("accountId");
                String ownerName = (String) body.get("ownerName");
                BigDecimal initialBalance = toBigDecimal(body.get("initialBalance"));
                String currency = (String) body.get("currency");

                if (accountId == null || ownerName == null) {
                    ctx.status(400).json(Map.of("error", "accountId and ownerName are required."));
                    return;
                }

                commandHandlers.handleCreateAccount(accountId, ownerName, initialBalance, currency);
                ctx.status(202).json(Map.of("message", "Account creation accepted.", "accountId", accountId));
            } catch (DomainException e) {
                mapError(e, ctx);
            } catch (Exception e) {
                log.error("Error creating account", e);
                ctx.status(500).json(Map.of("error", "Internal server error."));
            }
        });

        // POST /api/accounts/:accountId/deposit
        app.post("/api/accounts/{accountId}/deposit", ctx -> {
            try {
                String accountId = ctx.pathParam("accountId");
                Map<String, Object> body = parseBody(ctx);
                BigDecimal amount = toBigDecimal(body.get("amount"));
                String description = (String) body.get("description");
                String transactionId = (String) body.get("transactionId");

                if (body.get("amount") == null) {
                    ctx.status(400).json(Map.of("error", "amount is required."));
                    return;
                }
                if (transactionId == null) {
                    ctx.status(400).json(Map.of("error", "transactionId is required."));
                    return;
                }

                commandHandlers.handleDeposit(accountId, amount, description, transactionId);
                ctx.status(202).json(Map.of("message", "Deposit accepted.", "accountId", accountId));
            } catch (DomainException e) {
                mapError(e, ctx);
            } catch (Exception e) {
                log.error("Error processing deposit", e);
                ctx.status(500).json(Map.of("error", "Internal server error."));
            }
        });

        // POST /api/accounts/:accountId/withdraw
        app.post("/api/accounts/{accountId}/withdraw", ctx -> {
            try {
                String accountId = ctx.pathParam("accountId");
                Map<String, Object> body = parseBody(ctx);
                BigDecimal amount = toBigDecimal(body.get("amount"));
                String description = (String) body.get("description");
                String transactionId = (String) body.get("transactionId");

                if (body.get("amount") == null) {
                    ctx.status(400).json(Map.of("error", "amount is required."));
                    return;
                }
                if (transactionId == null) {
                    ctx.status(400).json(Map.of("error", "transactionId is required."));
                    return;
                }

                commandHandlers.handleWithdraw(accountId, amount, description, transactionId);
                ctx.status(202).json(Map.of("message", "Withdrawal accepted.", "accountId", accountId));
            } catch (DomainException e) {
                mapError(e, ctx);
            } catch (Exception e) {
                log.error("Error processing withdrawal", e);
                ctx.status(500).json(Map.of("error", "Internal server error."));
            }
        });

        // POST /api/accounts/:accountId/close
        app.post("/api/accounts/{accountId}/close", ctx -> {
            try {
                String accountId = ctx.pathParam("accountId");
                Map<String, Object> body = parseBody(ctx);
                String reason = (String) body.get("reason");

                commandHandlers.handleClose(accountId, reason);
                ctx.status(202).json(Map.of("message", "Account close accepted.", "accountId", accountId));
            } catch (DomainException e) {
                mapError(e, ctx);
            } catch (Exception e) {
                log.error("Error closing account", e);
                ctx.status(500).json(Map.of("error", "Internal server error."));
            }
        });

        // ─── ACCOUNT QUERIES (Read Side) ─────────────────────────────────

        // GET /api/accounts/:accountId
        app.get("/api/accounts/{accountId}", ctx -> {
            try {
                String accountId = ctx.pathParam("accountId");
                Map<String, Object> account = queryHandlers.getAccount(accountId);
                if (account == null) {
                    ctx.status(404).json(Map.of("error", "Account '" + accountId + "' not found."));
                    return;
                }
                ctx.status(200).json(account);
            } catch (Exception e) {
                log.error("Error getting account", e);
                ctx.status(500).json(Map.of("error", "Internal server error."));
            }
        });

        // GET /api/accounts/:accountId/events
        app.get("/api/accounts/{accountId}/events", ctx -> {
            try {
                String accountId = ctx.pathParam("accountId");
                List<Map<String, Object>> events = queryHandlers.getEventStream(accountId);
                if (events.isEmpty()) {
                    ctx.status(404).json(Map.of("error", "No events found for account '" + accountId + "'."));
                    return;
                }
                ctx.status(200).json(events);
            } catch (Exception e) {
                log.error("Error getting events", e);
                ctx.status(500).json(Map.of("error", "Internal server error."));
            }
        });

        // GET /api/accounts/:accountId/balance-at/:timestamp
        app.get("/api/accounts/{accountId}/balance-at/{timestamp}", ctx -> {
            try {
                String accountId = ctx.pathParam("accountId");
                String timestamp = URLDecoder.decode(ctx.pathParam("timestamp"), StandardCharsets.UTF_8);
                Map<String, Object> result = queryHandlers.getBalanceAt(accountId, timestamp);
                if (result == null) {
                    ctx.status(404).json(Map.of("error", "Account '" + accountId + "' not found."));
                    return;
                }
                ctx.status(200).json(result);
            } catch (Exception e) {
                log.error("Error getting balance at time", e);
                ctx.status(500).json(Map.of("error", "Internal server error."));
            }
        });

        // GET /api/accounts/:accountId/transactions
        app.get("/api/accounts/{accountId}/transactions", ctx -> {
            try {
                String accountId = ctx.pathParam("accountId");
                int page = Math.max(1, intQueryParam(ctx, "page", 1));
                int pageSize = Math.max(1, intQueryParam(ctx, "pageSize", 10));
                Map<String, Object> result = queryHandlers.getTransactions(accountId, page, pageSize);
                ctx.status(200).json(result);
            } catch (Exception e) {
                log.error("Error getting transactions", e);
                ctx.status(500).json(Map.of("error", "Internal server error."));
            }
        });

        // ─── PROJECTION ROUTES ───────────────────────────────────────────

        // POST /api/projections/rebuild
        app.post("/api/projections/rebuild", ctx -> {
            try {
                // Fire-and-forget on a Virtual Thread
                projector.rebuildAllProjectionsAsync();
                ctx.status(202).json(Map.of("message", "Projection rebuild initiated."));
            } catch (Exception e) {
                log.error("Error initiating rebuild", e);
                ctx.status(500).json(Map.of("error", "Failed to initiate rebuild."));
            }
        });

        // GET /api/projections/status
        app.get("/api/projections/status", ctx -> {
            try {
                Map<String, Object> status = projector.getProjectionStatus();
                ctx.status(200).json(status);
            } catch (Exception e) {
                log.error("Error getting projection status", e);
                ctx.status(500).json(Map.of("error", "Failed to get projection status."));
            }
        });

        // ─── 404 Catch-All ───────────────────────────────────────────────
        app.error(404, ctx -> {
            ctx.json(Map.of("error", "Not found."));
        });
    }

    // ─── Error Mapping ───────────────────────────────────────────────────────

    private void mapError(DomainException e, Context ctx) {
        int status = switch (e.getCode()) {
            case "VALIDATION_ERROR", "INVALID_AMOUNT" -> 400;
            case "NOT_FOUND" -> 404;
            case "CONFLICT", "ACCOUNT_CLOSED", "INSUFFICIENT_FUNDS", "NON_ZERO_BALANCE" -> 409;
            default -> 500;
        };
        ctx.status(status).json(Map.of("error", e.getMessage()));
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(Context ctx) throws Exception {
        String body = ctx.body();
        if (body == null || body.isBlank()) return Map.of();
        return mapper.readValue(body, Map.class);
    }

    private int intQueryParam(Context ctx, String name, int defaultValue) {
        String val = ctx.queryParam(name);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(value.toString());
    }
}

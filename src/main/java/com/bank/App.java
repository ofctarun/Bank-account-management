package com.bank;

import com.bank.commands.CommandHandlers;
import com.bank.infrastructure.DatabaseManager;
import com.bank.infrastructure.EventStore;
import com.bank.projections.AccountProjector;
import com.bank.queries.QueryHandlers;
import com.bank.routes.ApiRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application Entry Point.
 * <p>
 * Wires together the infrastructure, domain, and API layers.
 * No dependency injection framework — explicit constructor wiring
 * to demonstrate understanding of the composition root pattern.
 * </p>
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {

        // ─── Configuration from Environment ──────────────────────────────
        int port = intEnv("API_PORT", 8080);
        String dbUrl = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/bank_db");
        String dbUser = env("DB_USER", "bank_user");
        String dbPassword = env("DB_PASSWORD", "bank_password");

        // Parse DATABASE_URL — supports postgresql://user:pass@host:port/db format
        // JDBC needs: jdbc:postgresql://host:port/db (credentials separate)
        if (dbUrl.startsWith("postgresql://") || dbUrl.startsWith("postgres://")) {
            try {
                String cleaned = dbUrl.replaceFirst("^postgres://", "postgresql://");
                java.net.URI uri = new java.net.URI(cleaned);
                String userInfo = uri.getUserInfo();
                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    dbUser = parts[0];
                    dbPassword = parts[1];
                }
                dbUrl = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
            } catch (Exception e) {
                log.warn("Could not parse DATABASE_URL, using as-is: {}", e.getMessage());
                dbUrl = "jdbc:" + dbUrl.replaceFirst("^postgres://", "postgresql://");
            }
        } else if (!dbUrl.startsWith("jdbc:")) {
            dbUrl = "jdbc:" + dbUrl;
        }

        // ─── Initialize Database Pool ────────────────────────────────────
        log.info("Initializing database connection pool...");
        DatabaseManager.init(dbUrl, dbUser, dbPassword);

        // ─── Wire Dependencies (Composition Root) ────────────────────────
        EventStore eventStore = new EventStore();
        AccountProjector projector = new AccountProjector();
        CommandHandlers commandHandlers = new CommandHandlers(eventStore, projector);
        QueryHandlers queryHandlers = new QueryHandlers(eventStore);
        ApiRouter apiRouter = new ApiRouter(commandHandlers, queryHandlers, projector);

        // ─── Configure Jackson for Javalin ───────────────────────────────
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // ─── Create and Start Javalin Server ─────────────────────────────
        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(objectMapper, false));

            // CORS — allow React dev server and production origins
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    rule.anyHost(); // In production, restrict to your domain
                });
            });
        });

        // Request logger middleware
        app.before(ctx -> {
            log.info("[{}] {} {}", java.time.Instant.now(), ctx.method(), ctx.url());
        });

        // Register all routes
        apiRouter.registerRoutes(app);

        // Global exception handler
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled error:", e);
            ctx.status(500).json(java.util.Map.of("error", "Internal server error."));
        });

        // ─── Graceful Shutdown ───────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            app.stop();
            DatabaseManager.shutdown();
            log.info("Shutdown complete.");
        }));

        // ─── Start ──────────────────────────────────────────────────────
        app.start("0.0.0.0", port);
        log.info("Bank Account Management API listening on port {}", port);
    }

    // ─── Env Helpers ─────────────────────────────────────────────────────────

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private static int intEnv(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

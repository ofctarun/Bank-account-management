# ──────────────────────────────────────────────────────────────
# Multi-stage build for FinTrace — Java 21
# ──────────────────────────────────────────────────────────────

# Stage 1: Build with Maven
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Copy POM first for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build fat JAR
COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Lightweight runtime
FROM eclipse-temurin:21-jre-alpine

# Install curl for healthcheck
RUN apk add --no-cache curl

WORKDIR /app

# Copy the fat JAR from builder
COPY --from=builder /app/target/fintrace-1.0.0.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
    CMD curl -f http://localhost:${API_PORT:-8080}/health || exit 1

CMD ["java", "-jar", "app.jar"]

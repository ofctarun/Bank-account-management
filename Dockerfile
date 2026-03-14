FROM node:20-alpine

# Install curl for healthcheck
RUN apk add --no-cache curl

WORKDIR /app

# Install dependencies first (layer caching)
COPY package*.json ./
RUN npm install --production

# Copy source
COPY . .

EXPOSE 8080

# Health check
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -f http://localhost:${API_PORT:-8080}/health || exit 1

CMD ["node", "src/index.js"]

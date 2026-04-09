# ============ Stage 1: Build Angular SSR ============
FROM node:20-alpine AS builder
WORKDIR /app

# Copy dependency files first (better layer caching)
COPY package*.json ./
RUN npm ci --prefer-offline

# Copy source code and build in production mode
COPY . .
RUN npm run build -- --configuration production

# ============ Stage 2: Run SSR Server ============
FROM node:20-alpine
WORKDIR /app

# Copy only the built output (no node_modules from source)
COPY --from=builder /app/dist/documents-validate-gov ./dist/documents-validate-gov

# Install only production dependencies for the SSR server
COPY package*.json ./
RUN npm ci --omit=dev --prefer-offline

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN chown -R appuser:appgroup /app
USER appuser

# The SSR server listens on PORT env var, defaults to 4000
ENV PORT=4000
EXPOSE 4000

CMD ["node", "dist/documents-validate-gov/server/server.mjs"]

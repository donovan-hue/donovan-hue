# Se usa Debian slim (glibc) para que better-sqlite3 instale su binario
# precompilado sin necesitar cadena de compilación en la imagen.

# ── Etapa 1: compilación ──────────────────────────────────────────────────────
FROM node:20-slim AS builder
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

# ── Etapa 2: runtime (solo dependencias de producción) ────────────────────────
FROM node:20-slim
WORKDIR /app
ENV NODE_ENV=production \
    MCP_TRANSPORT=http \
    HOST=0.0.0.0 \
    PORT=8787 \
    DATA_DIR=/app/data \
    AUTH_MODE=required
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force
COPY --from=builder /app/dist ./dist
COPY paginas ./paginas
COPY data ./data
# Volumen persistente: SQLite, auditoría y datasets.
RUN mkdir -p /app/data/runtime
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8787)+'/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["node", "dist/index.js", "--transport", "http"]

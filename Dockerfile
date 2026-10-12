# Imagen en dos etapas. La cadena de compilación vive solo en la etapa «builder»:
# ahí se compila el módulo nativo (better-sqlite3) y el TypeScript, y de ahí salen
# los node_modules ya listos. La imagen final no lleva compilador ni devDependencies.

# ── Etapa 1: compilación ──────────────────────────────────────────────────────
FROM node:20-slim AS builder
WORKDIR /app
# En node:20-slim, prebuild-install no encuentra binario precompilado de
# better-sqlite3 (no detecta la libc), así que cae a compilar: hacen falta estas
# herramientas. Aquí no estorban; no se copian a la imagen final.
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 make g++ \
 && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

# ── Etapa 2: runtime (dependencias de producción ya compiladas) ───────────────
FROM node:20-slim
WORKDIR /app
ENV NODE_ENV=production \
    MCP_TRANSPORT=http \
    HOST=0.0.0.0 \
    PORT=8787 \
    DATA_DIR=/app/data \
    AUTH_MODE=required
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/dist ./dist
COPY package.json package-lock.json ./
COPY paginas ./paginas
COPY data ./data
# Volumen persistente: SQLite, auditoría y datasets.
RUN mkdir -p /app/data/runtime
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8787)+'/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["node", "dist/index.js", "--transport", "http"]

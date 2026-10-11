import fs from 'node:fs/promises';
import path from 'node:path';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { loadConfig, type Config } from './config.js';
import { setLogLevel, log } from './logger.js';
import { buildServer } from './server.js';
import { startHttp } from './http.js';
import { ensureSeedData } from './seed.js';
import { SERVER_NAME, VERSION } from './version.js';
import { Metricas } from './lib/metrics.js';
import { Auditoria } from './lib/audit.js';
import { LimitadorTasas } from './lib/rate-limit.js';
import { contarClavesActivas } from './auth/keys.js';
import { abrirDb } from './db/index.js';
import type { ServerCtx } from './context.js';

export type { Config };

/**
 * Construye el contexto compartido: métricas, auditoría y limitador de tasa.
 * La auditoría vive en DATA_DIR/runtime/ para que persista con el volumen de datos.
 */
export async function crearContexto(config: Config): Promise<ServerCtx> {
  const runtimeDir = path.join(config.dataDir, 'runtime');
  await fs.mkdir(runtimeDir, { recursive: true });

  const db = abrirDb(path.join(config.dataDir, 'suite.db'));

  const ctx: ServerCtx = {
    config,
    db,
    metrics: new Metricas(),
    audit: new Auditoria(path.join(runtimeDir, 'auditoria.ndjson')),
    limiter: config.rateLimitRpm > 0 ? new LimitadorTasas(config.rateLimitRpm, config.rateLimitBurst) : undefined,
  };

  if (config.transport === 'http') {
    if (config.authMode === 'off') {
      log.warn('seguridad', { mensaje: 'AUTH_MODE=off: cualquier cliente puede invocar herramientas. No exponer a internet.' });
    } else {
      const clavesDisco = await contarClavesActivas(ctx).catch(() => 0);
      log.info('seguridad', {
        auth_mode: config.authMode,
        claves_bootstrap: config.apiKeys.length,
        claves_emitidas: clavesDisco,
        rate_limit_rpm: config.rateLimitRpm,
      });
      if (config.authMode === 'required' && clavesDisco === 0 && config.apiKeys.length === 0) {
        log.warn('seguridad', { mensaje: 'AUTH_MODE=required sin claves: crea una con "node dist/cli.js keys create".' });
      }
    }
  }

  return ctx;
}

/** Arranca el servidor resolviendo el transporte indicado (stdio o http). */
export async function run(opts: { transport?: Config['transport'] } = {}): Promise<() => Promise<void>> {
  const config = loadConfig({ ...(opts.transport ? { transport: opts.transport } : {}) });
  setLogLevel(config.logLevel);

  const ctx = await crearContexto(config);
  await ensureSeedData(ctx);

  log.info('arrancando', { nombre: SERVER_NAME, version: VERSION, transporte: config.transport });

  if (config.transport === 'http') {
    const server = await startHttp(ctx);
    return async () => {
      await new Promise<void>((resolve) => server.close(() => resolve()));
    };
  }

  const { server } = buildServer(ctx);
  const transport = new StdioServerTransport();
  await server.connect(transport);
  log.info('stdio_listo', { mensaje: 'Servidor MCP escuchando en stdio. Listo para el cliente.' });
  return async () => {
    await server.close();
  };
}

const esEjecucionDirecta = process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href;

if ((esEjecucionDirecta && !process.argv[1]?.endsWith('cli.js')) || process.env.MCP_SUITE_AUTOSTART === '1') {
  run()
    .then((shutdown) => {
      const cerrar = async (signal: string) => {
        log.info('apagando', { signal });
        await shutdown().catch(() => undefined);
        process.exit(0);
      };
      process.on('SIGINT', () => void cerrar('SIGINT'));
      process.on('SIGTERM', () => void cerrar('SIGTERM'));
    })
    .catch((err) => {
      log.error('error_fatal', { error: err?.message ?? String(err), stack: err?.stack });
      process.exit(1);
    });
}

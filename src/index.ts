#!/usr/bin/env node
import fs from 'node:fs/promises';
import fsSync from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
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

/**
 * ¿Nos están ejecutando de verdad? Ojo: npm instala los binarios como enlaces
 * (node_modules/.bin/mcp-suite-mx), así que hay que comparar la ruta REAL del
 * archivo, no la del enlace; si no, el servidor instalado no arranca.
 */
function esEjecucionDirecta(): boolean {
  const entrada = process.argv[1];
  if (!entrada) return false;
  try {
    const real = fsSync.realpathSync(entrada);
    return import.meta.url === pathToFileURL(real).href;
  } catch {
    return false;
  }
}

/** Aviso corto de uso para `mcp-suite-mx --help` (y `npx mcp-suite-mx --version`). */
function ayudaBasica(): string {
  return [
    `${SERVER_NAME} ${VERSION} — plataforma MCP para empresas mexicanas.`,
    '',
    'Uso:',
    '  mcp-suite-mx                    Servidor MCP por stdio (Claude Desktop, Cursor, …)',
    '  mcp-suite-mx --transport http   Servidor MCP por HTTP (panel web en /panel)',
    '  mcp-suite-mx-cli <comando>      Administración: keys, audit, backup, empresas, parches, flujos, modelos, seguridad',
    '',
    'Variables: DATA_DIR (por defecto ./data), PORT (8787), AUTH_MODE (off|optional|required),',
    '           ALLOWED_GIT_ROOTS (rutas autorizadas para herramientas de proyecto), EMPRESA_DEFAULT.',
    '',
    'Ayuda de administración:  mcp-suite-mx-cli   (sin argumentos)',
  ].join('\n');
}

const ejecucionDirecta = esEjecucionDirecta();

if (ejecucionDirecta) {
  const args = process.argv.slice(2);
  if (args.includes('--version') || args.includes('-v')) {
    process.stdout.write(`${VERSION}\n`);
    process.exit(0);
  }
  if (args.includes('--help') || args.includes('-h')) {
    process.stdout.write(`${ayudaBasica()}\n`);
    process.exit(0);
  }
}

if ((ejecucionDirecta && !process.argv[1]?.endsWith('cli.js')) || process.env.MCP_SUITE_AUTOSTART === '1') {
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

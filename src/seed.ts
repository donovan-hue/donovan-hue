import type { ServerCtx } from './context.js';
import { migrarDesdeJson, sembrarSiVacio } from './db/index.js';
import { log } from './logger.js';

/**
 * Prepara la base de datos al arrancar:
 * 1. Migra los JSON de la fase 1 si existen y las tablas están vacías (no borra los JSON).
 * 2. Siembra los datos de ejemplo en una instalación nueva.
 */
export async function ensureSeedData(ctx: ServerCtx): Promise<void> {
  const migrados = migrarDesdeJson(ctx.db, ctx.config.dataDir);
  sembrarSiVacio(ctx.db);
  log.debug('datos_iniciales_verificados', {
    dataDir: ctx.config.dataDir,
    migraciones: migrados.length,
  });
}

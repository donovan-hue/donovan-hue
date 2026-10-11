import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { loadConfig, type Config } from '../src/config';
import { abrirDb } from '../src/db';
import { Auditoria } from '../src/lib/audit';
import { Metricas } from '../src/lib/metrics';
import { LimitadorTasas } from '../src/lib/rate-limit';
import type { ServerCtx } from '../src/context';

/** Directorio temporal aislado por suite de pruebas. */
export async function dataDirTemporal(prefijo: string): Promise<string> {
  return fs.mkdtemp(path.join(os.tmpdir(), prefijo));
}

/** Contexto completo (BD + métricas + auditoría + limitador) para pruebas. */
export async function contextoDePrueba(
  dataDir: string,
  overrides: Partial<Config> = {},
  limites?: { rpm: number; burst: number },
): Promise<ServerCtx> {
  const config = loadConfig({ transport: 'stdio', dataDir, ...overrides });
  await fs.mkdir(path.join(dataDir, 'runtime'), { recursive: true });
  return {
    config,
    db: abrirDb(path.join(dataDir, 'suite.db')),
    metrics: new Metricas(),
    audit: new Auditoria(path.join(dataDir, 'runtime', 'auditoria.ndjson')),
    limiter: limites ? new LimitadorTasas(limites.rpm, limites.burst) : undefined,
  };
}

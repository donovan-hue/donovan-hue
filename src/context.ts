import type { Config } from './config.js';
import type { Auditoria } from './lib/audit.js';
import type { Metricas } from './lib/metrics.js';
import type { LimitadorTasas } from './lib/rate-limit.js';
import type { DB } from './db/index.js';

/** Identidad autenticada de la petición actual (indefinida en modo off/stdio sin clave). */
export interface IdentidadCliente {
  id: string;
  nombre: string;
  scopes: string[];
  origen: 'api_key' | 'bootstrap';
}

/** Contexto compartido que reciben todos los módulos de herramientas. */
export interface ServerCtx {
  config: Config;
  /** Base de datos SQLite (WAL) con inventario, pedidos, plantillas y claves. */
  db: DB;
  metrics: Metricas;
  audit: Auditoria;
  /** Limitador por clave/IP; undefined cuando RATE_LIMIT_RPM=0. */
  limiter?: LimitadorTasas;
  /** Identidad del cliente que hizo la petición (si está autenticado). */
  apiKey?: IdentidadCliente;
}

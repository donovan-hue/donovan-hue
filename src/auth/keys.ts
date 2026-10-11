import crypto from 'node:crypto';
import type { ServerCtx } from '../context.js';

export interface ApiKey {
  id: string;
  nombre: string;
  /** sha256 de la clave completa; el secreto jamás se guarda en claro. */
  hash: string;
  scopes: string[];
  creada: string;
  expira: string | null;
  activa: boolean;
  ultimo_uso: string | null;
  usos: number;
}

export interface ResultadoVerificacion {
  ok: boolean;
  motivo?: string;
  key?: ApiKey;
}

export interface ResultadoCreacion {
  key: ApiKey;
  /** Clave en claro: se muestra UNA sola vez, no queda almacenada. */
  clave: string;
}

export const SCOPES_VALIDOS = [
  '*',
  'core',
  'negocio',
  'negocio:ro',
  'facturacion',
  'facturacion:ro',
  'documentos',
  'documentos:ro',
  'analisis',
  'analisis:ro',
  'devops',
  'devops:ro',
  'taller',
  'taller:ro',
  'centinela',
  'centinela:ro',
  'parche',
  'parche:ro',
  'orquesta',
  'orquesta:ro',
  'ruter',
  'ruter:ro',
];

interface FilaClave {
  id: string;
  nombre: string;
  hash: string;
  scopes: string;
  creada: string;
  expira: string | null;
  activa: number;
  ultimo_uso: string | null;
  usos: number;
}

function aApiKey(fila: FilaClave): ApiKey {
  return { ...fila, scopes: JSON.parse(fila.scopes) as string[], activa: fila.activa === 1 };
}

function hashear(clave: string): string {
  return crypto.createHash('sha256').update(clave, 'utf8').digest('hex');
}

function compararSeguro(a: string, b: string): boolean {
  const ba = Buffer.from(a, 'hex');
  const bb = Buffer.from(b, 'hex');
  return ba.length === bb.length && crypto.timingSafeEqual(ba, bb);
}

export function validarScopes(scopes: string[]): void {
  if (scopes.length === 0) throw new Error('Debes indicar al menos un scope.');
  const invalidos = scopes.filter((s) => !SCOPES_VALIDOS.includes(s));
  if (invalidos.length > 0) {
    throw new Error(`Scopes inválidos: ${invalidos.join(', ')}. Válidos: ${SCOPES_VALIDOS.join(', ')}.`);
  }
}

/** Crea una clave nueva. Devuelve el secreto en claro una única vez. */
export async function crearClave(
  ctx: ServerCtx,
  opciones: { nombre: string; scopes: string[]; expiraDias?: number },
): Promise<ResultadoCreacion> {
  const nombre = opciones.nombre.trim();
  if (nombre.length < 3) throw new Error('El nombre de la clave debe tener al menos 3 caracteres.');
  validarScopes(opciones.scopes);
  if (opciones.expiraDias !== undefined && (!Number.isFinite(opciones.expiraDias) || opciones.expiraDias <= 0)) {
    throw new Error('expiraDias debe ser un número mayor que cero.');
  }

  const id = crypto.randomBytes(4).toString('hex'); // 8 hex chars
  const secreto = crypto.randomBytes(24).toString('base64url');
  const clave = `mcp_${id}_${secreto}`;
  const registro: ApiKey = {
    id,
    nombre,
    hash: hashear(clave),
    scopes: opciones.scopes,
    creada: new Date().toISOString(),
    expira: opciones.expiraDias ? new Date(Date.now() + opciones.expiraDias * 86_400_000).toISOString() : null,
    activa: true,
    ultimo_uso: null,
    usos: 0,
  };

  const existe = ctx.db.prepare(`SELECT 1 FROM api_keys WHERE LOWER(nombre) = LOWER(?)`).get(nombre);
  if (existe) throw new Error(`Ya existe una clave con el nombre "${nombre}".`);

  ctx.db
    .prepare(
      `INSERT INTO api_keys (id, nombre, hash, scopes, creada, expira, activa, ultimo_uso, usos)
       VALUES (@id, @nombre, @hash, @scopes, @creada, @expira, 1, NULL, 0)`,
    )
    .run({ ...registro, scopes: JSON.stringify(registro.scopes), expira: registro.expira });

  return { key: registro, clave };
}

/**
 * Verifica una clave presentada: compara hashes en tiempo constante y
 * actualiza métricas de uso. Devuelve siempre un resultado explícito.
 */
export async function verificarClave(ctx: ServerCtx, presentada: string): Promise<ResultadoVerificacion> {
  if (!presentada || typeof presentada !== 'string') return { ok: false, motivo: 'Clave ausente.' };
  const partes = presentada.match(/^mcp_([0-9a-f]{8})_(.+)$/);
  if (!partes) return { ok: false, motivo: 'Formato de clave inválido (se espera mcp_XXXXXXXX_secreto).' };

  const fila = ctx.db.prepare(`SELECT * FROM api_keys WHERE id = ?`).get(partes[1]!) as FilaClave | undefined;
  if (!fila) return { ok: false, motivo: 'Clave no reconocida.' };

  if (!compararSeguro(fila.hash, hashear(presentada))) return { ok: false, motivo: 'Clave no reconocida.' };
  if (fila.activa !== 1) return { ok: false, motivo: 'Clave revocada.' };
  if (fila.expira && Date.parse(fila.expira) < Date.now()) {
    return { ok: false, motivo: `Clave expirada el ${fila.expira.slice(0, 10)}.` };
  }

  const ahora = new Date().toISOString();
  ctx.db.prepare(`UPDATE api_keys SET ultimo_uso = ?, usos = usos + 1 WHERE id = ?`).run(ahora, fila.id);
  return { ok: true, key: { ...aApiKey(fila), ultimo_uso: ahora, usos: fila.usos + 1 } };
}

export async function listarClaves(ctx: ServerCtx): Promise<Omit<ApiKey, 'hash'>[]> {
  const filas = ctx.db.prepare(`SELECT * FROM api_keys ORDER BY creada`).all() as FilaClave[];
  return filas.map(({ hash: _hash, ...resto }) => {
    const k = aApiKey({ ...resto, hash: '' } as FilaClave);
    const { hash: _h, ...sinHash } = k;
    return sinHash;
  });
}

export async function revocarClave(ctx: ServerCtx, idONombre: string): Promise<{ id: string; nombre: string }> {
  const fila = ctx.db
    .prepare(`SELECT id, nombre, activa FROM api_keys WHERE id = ? OR LOWER(nombre) = LOWER(?)`)
    .get(idONombre, idONombre) as { id: string; nombre: string; activa: number } | undefined;
  if (!fila) throw new Error(`No existe una clave con id o nombre "${idONombre}".`);
  if (fila.activa !== 1) throw new Error(`La clave "${fila.nombre}" ya estaba revocada.`);
  ctx.db.prepare(`UPDATE api_keys SET activa = 0 WHERE id = ?`).run(fila.id);
  return { id: fila.id, nombre: fila.nombre };
}

export async function contarClavesActivas(ctx: ServerCtx): Promise<number> {
  const fila = ctx.db
    .prepare(`SELECT COUNT(*) AS n FROM api_keys WHERE activa = 1 AND (expira IS NULL OR expira > ?)`)
    .get(new Date().toISOString()) as { n: number };
  return fila.n;
}

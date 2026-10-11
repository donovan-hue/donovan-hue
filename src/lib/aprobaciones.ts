/**
 * GATE DE APROBACIÓN (fase 4B) — ninguna operación destructiva se ejecuta sin
 * una aprobación humana explícita, registrada e inmutable.
 *
 * Flujo: una herramienta `solicitar()` una aprobación → queda en la tabla
 * `aprobaciones` en estado `pendiente` → la persona la aprueba o rechaza por
 * CLI (`aprobar <id>`) → la herramienta `exigir()` antes de actuar.
 * Al resolverse se escribe un evento inmutable en la auditoría.
 *
 * La aprobación se hace SIEMPRE fuera del canal del modelo (CLI o panel), para
 * que un agente no pueda autorizarse a sí mismo.
 */
import crypto from 'node:crypto';
import type { ServerCtx } from '../context.js';

export type EstadoAprobacion = 'pendiente' | 'aprobada' | 'rechazada' | 'expirada';

export interface Aprobacion {
  id: string;
  tipo: string;
  titulo: string;
  detalle: string;
  estado: EstadoAprobacion;
  solicitada_en: string;
  solicitada_por: string;
  resuelta_en?: string;
  resuelta_por?: string;
  motivo?: string;
  /** Huella del contenido aprobado: si cambia el plan, la aprobación no sirve. */
  huella?: string;
}

interface FilaAprobacion {
  id: string;
  tipo: string;
  titulo: string;
  detalle: string;
  estado: EstadoAprobacion;
  solicitada_en: string;
  solicitada_por: string;
  resuelta_en: string | null;
  resuelta_por: string | null;
  motivo: string | null;
  huella: string | null;
}

/** Vigencia de una aprobación pendiente (min). 0 = sin caducidad. */
const VIGENCIA_MIN = Number(process.env.APROBACION_VIGENCIA_MIN ?? 120);

const aAprobacion = (f: FilaAprobacion): Aprobacion => ({
  id: f.id,
  tipo: f.tipo,
  titulo: f.titulo,
  detalle: f.detalle,
  estado: f.estado,
  solicitada_en: f.solicitada_en,
  solicitada_por: f.solicitada_por,
  resuelta_en: f.resuelta_en ?? undefined,
  resuelta_por: f.resuelta_por ?? undefined,
  motivo: f.motivo ?? undefined,
  huella: f.huella ?? undefined,
});

export function huellaDeContenido(contenido: string): string {
  return crypto.createHash('sha256').update(contenido).digest('hex').slice(0, 16);
}

/** Crea una solicitud de aprobación (queda pendiente hasta que una persona la resuelva). */
export async function solicitarAprobacion(
  ctx: ServerCtx,
  datos: { tipo: string; titulo: string; detalle: string; huella?: string },
): Promise<Aprobacion> {
  const id = `AP-${crypto.randomBytes(3).toString('hex')}`;
  const ahora = new Date().toISOString();
  const quien = ctx.apiKey?.nombre ?? 'local';
  ctx.db
    .prepare(
      `INSERT INTO aprobaciones (id, tipo, titulo, detalle, estado, solicitada_en, solicitada_por, huella)
       VALUES (?, ?, ?, ?, 'pendiente', ?, ?, ?)`,
    )
    .run(id, datos.tipo, datos.titulo, datos.detalle, ahora, quien, datos.huella ?? null);

  await ctx.audit.registrar({
    tipo: 'aprobacion_solicitada',
    cliente: quien,
    herramienta: 'gate_aprobacion',
    detalle: `${id} · ${datos.tipo} · ${datos.titulo}`,
  });

  return {
    id,
    tipo: datos.tipo,
    titulo: datos.titulo,
    detalle: datos.detalle,
    estado: 'pendiente',
    solicitada_en: ahora,
    solicitada_por: quien,
    huella: datos.huella,
  };
}

export function obtenerAprobacion(ctx: ServerCtx, id: string): Aprobacion | undefined {
  const fila = ctx.db.prepare('SELECT * FROM aprobaciones WHERE id = ?').get(id) as FilaAprobacion | undefined;
  if (!fila) return undefined;
  return aAprobacion(fila);
}

export function listarAprobaciones(
  ctx: ServerCtx,
  opciones?: { estado?: EstadoAprobacion; limite?: number },
): Aprobacion[] {
  const condiciones: string[] = [];
  const valores: unknown[] = [];
  if (opciones?.estado) {
    condiciones.push('estado = ?');
    valores.push(opciones.estado);
  }
  const donde = condiciones.length ? `WHERE ${condiciones.join(' AND ')}` : '';
  const filas = ctx.db
    .prepare(`SELECT * FROM aprobaciones ${donde} ORDER BY solicitada_en DESC LIMIT ?`)
    .all(...valores, opciones?.limite ?? 50) as FilaAprobacion[];
  return filas.map(aAprobacion);
}

/** Marca como expiradas las aprobaciones pendientes que superaron su vigencia. */
export function expirarVencidas(ctx: ServerCtx): number {
  if (VIGENCIA_MIN <= 0) return 0;
  const limite = new Date(Date.now() - VIGENCIA_MIN * 60_000).toISOString();
  const r = ctx.db
    .prepare(`UPDATE aprobaciones SET estado = 'expirada', resuelta_en = ?, motivo = 'vigencia agotada' WHERE estado = 'pendiente' AND solicitada_en < ?`)
    .run(new Date().toISOString(), limite);
  return r.changes;
}

/** Resuelve una aprobación (aprobada o rechazada). Solo desde CLI/panel, nunca desde el modelo. */
export async function resolverAprobacion(
  ctx: ServerCtx,
  id: string,
  estado: Extract<EstadoAprobacion, 'aprobada' | 'rechazada'>,
  opciones?: { por?: string; motivo?: string },
): Promise<Aprobacion> {
  expirarVencidas(ctx);
  const actual = obtenerAprobacion(ctx, id);
  if (!actual) throw new Error(`No existe la aprobación ${id}.`);
  if (actual.estado !== 'pendiente') {
    throw new Error(`La aprobación ${id} ya está en estado "${actual.estado}" y no se puede cambiar.`);
  }
  const por = opciones?.por ?? 'cli';
  ctx.db
    .prepare(`UPDATE aprobaciones SET estado = ?, resuelta_en = ?, resuelta_por = ?, motivo = ? WHERE id = ?`)
    .run(estado, new Date().toISOString(), por, opciones?.motivo ?? null, id);

  await ctx.audit.registrar({
    tipo: estado === 'aprobada' ? 'aprobacion_aprobada' : 'aprobacion_rechazada',
    cliente: por,
    herramienta: 'gate_aprobacion',
    detalle: `${id} · ${actual.tipo} · ${actual.titulo}${opciones?.motivo ? ` · motivo: ${opciones.motivo}` : ''}`,
  });

  return { ...actual, estado, resuelta_en: new Date().toISOString(), resuelta_por: por, motivo: opciones?.motivo };
}

export interface ResultadoExigir {
  permitido: boolean;
  motivo?: string;
  aprobacion?: Aprobacion;
}

/**
 * Comprueba que exista una aprobación válida para actuar. Si se pasa `huella`,
 * la aprobación debe corresponder al MISMO contenido (si el plan cambió, hay
 * que volver a aprobar).
 */
export function exigirAprobacion(ctx: ServerCtx, id: string | undefined, huella?: string): ResultadoExigir {
  if (!id) {
    return { permitido: false, motivo: 'Falta la aprobación: solicítala y apruébala por CLI antes de ejecutar una operación destructiva.' };
  }
  expirarVencidas(ctx);
  const ap = obtenerAprobacion(ctx, id);
  if (!ap) return { permitido: false, motivo: `La aprobación ${id} no existe.` };
  if (ap.estado === 'pendiente') {
    return { permitido: false, motivo: `La aprobación ${id} sigue pendiente. Apruébala con: node dist/cli.js aprobar ${id}`, aprobacion: ap };
  }
  if (ap.estado !== 'aprobada') return { permitido: false, motivo: `La aprobación ${id} está "${ap.estado}" y no autoriza la operación.`, aprobacion: ap };
  if (huella && ap.huella && ap.huella !== huella) {
    return { permitido: false, motivo: `El contenido cambió desde que se aprobó ${id} (huella distinta). Vuelve a solicitar aprobación.`, aprobacion: ap };
  }
  return { permitido: true, aprobacion: ap };
}

/** Uso en herramientas: lanza un error claro si no hay aprobación válida. */
export function requiereAprobacion(ctx: ServerCtx, id: string | undefined, huella?: string): Aprobacion {
  const r = exigirAprobacion(ctx, id, huella);
  if (!r.permitido) throw new Error(r.motivo);
  return r.aprobacion!;
}

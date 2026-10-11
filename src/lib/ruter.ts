/**
 * RÚTER (fase 4D) — elegir modelo por tarea y medir COSTE POR RESULTADO ÚTIL.
 *
 * A diferencia de los enrutadores que estiman calidad por benchmark, aquí la
 * calidad se mide con hechos del propio taller: ¿las pruebas quedaron verdes?,
 * ¿el parche sobrevivió?, ¿el escaneo mejoró? Ese resultado se registra y el
 * consejo de modelo se recalibra con datos reales.
 *
 * El catálogo de modelos es LOCAL y EDITABLE (data/ruter/modelos.json). Los
 * costes son unidades relativas: no son precios de ningún proveedor; adáptalos
 * a tu factura real.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import type { ServerCtx } from '../context.js';
import type { DB } from '../db/index.js';

export interface Modelo {
  id: string;
  nombre: string;
  donde: 'local' | 'nube';
  fuerza: 1 | 2 | 3;
  coste_relativo: number;
  latencia: 'baja' | 'media' | 'alta';
  nota?: string;
}

export interface Sugerencia {
  nivel: 1 | 2 | 3;
  motivo: string;
  cascada: { orden: number; modelo: string; id: string; por_que: string }[];
  presupuesto_sugerido: string;
  regla_de_escalado: string;
  ajustes_por_datos: string[];
  advertencias: string[];
}

export const CATALOGO_EJEMPLO: Modelo[] = [
  { id: 'local-rapido', nombre: 'Modelo local pequeño', donde: 'local', fuerza: 1, coste_relativo: 0, latencia: 'baja', nota: 'Corre en tu máquina (p. ej. Ollama). Ideal: formato, renombrados, resúmenes cortos.' },
  { id: 'local-grande', nombre: 'Modelo local grande', donde: 'local', fuerza: 2, coste_relativo: 0, latencia: 'media', nota: 'Sin costo por uso; pide más máquina. Ideal cuando el código no puede salir.' },
  { id: 'nube-rapida', nombre: 'Modelo de nube económico', donde: 'nube', fuerza: 2, coste_relativo: 1, latencia: 'baja' },
  { id: 'nube-media', nombre: 'Modelo de nube intermedio', donde: 'nube', fuerza: 2, coste_relativo: 3, latencia: 'media' },
  { id: 'nube-fuerte', nombre: 'Modelo de nube frontera', donde: 'nube', fuerza: 3, coste_relativo: 10, latencia: 'alta', nota: 'Para lo complejo o lo crítico: migraciones, arquitectura, seguridad.' },
];

const TIPOS_SIMPLES = /renombr|formato|lint|typo|comentario|traduc|plantilla|resumen|ordenar/i;
const TIPOS_COMPLEJOS = /migraci|arquitectura|redise|seguridad|rendimiento|performance|concurren|refactor.*grande|dise[ñn]o/i;
const TIPOS_MEDIOS = /refactor|prueba|test|bug|falla|documenta|mejora|ajuste|repar|parche/i;

interface DatosHistoricos {
  modelo: string;
  tareas: number;
  exitos: number;
  coste_total: number;
}

/** Lee el catálogo editable; si no existe, deja el de ejemplo. */
export async function leerCatalogo(ctx: ServerCtx): Promise<Modelo[]> {
  const archivo = path.join(ctx.config.dataDir, 'ruter', 'modelos.json');
  try {
    const crudo = JSON.parse(await fs.readFile(archivo, 'utf8'));
    if (Array.isArray(crudo.modelos) && crudo.modelos.length) return crudo.modelos as Modelo[];
  } catch {
    /* primera vez */
  }
  await fs.mkdir(path.dirname(archivo), { recursive: true });
  await fs.writeFile(archivo, JSON.stringify({ modelos: CATALOGO_EJEMPLO, nota: 'Costes en unidades relativas: edítalos con tus precios reales.' }, null, 2), 'utf8');
  return CATALOGO_EJEMPLO;
}

/** Historial agregado por modelo (fuente: tabla costos_modelo). */
export function historialPorModelo(db: DB, tipo?: string): DatosHistoricos[] {
  const filas = db
    .prepare(
      `SELECT modelo, COUNT(*) AS tareas, SUM(exito) AS exitos, SUM(coste) AS coste_total
       FROM costos_modelo ${tipo ? 'WHERE tipo = ?' : ''} GROUP BY modelo`,
    )
    .all(...(tipo ? [tipo] : [])) as unknown as { modelo: string; tareas: number; exitos: number; coste_total: number }[];
  return filas.map((f) => ({ modelo: f.modelo, tareas: f.tareas, exitos: Number(f.exitos ?? 0), coste_total: Number(f.coste_total ?? 0) }));
}

export function clasificarTarea(entrada: {
  descripcion: string;
  tipo?: string;
  archivos_tocados?: number;
  criticidad?: 'baja' | 'media' | 'alta';
  privacidad?: 'puede_salir' | 'no_sale';
}): { nivel: 1 | 2 | 3; motivo: string } {
  const texto = `${entrada.tipo ?? ''} ${entrada.descripcion}`;
  let nivel: 1 | 2 | 3 = TIPOS_SIMPLES.test(texto) ? 1 : TIPOS_COMPLEJOS.test(texto) ? 3 : TIPOS_MEDIOS.test(texto) ? 2 : 2;
  const razones: string[] = [];
  if (nivel === 1) razones.push('tarea mecánica o repetitiva');
  if (nivel === 2) razones.push('tarea de trabajo normal (toca lógica, pruebas o documentación)');
  if (nivel === 3) razones.push('tarea de alto riesgo o alcance amplio');

  const n = entrada.archivos_tocados ?? 0;
  if (n >= 10 && nivel < 3) {
    nivel = (nivel + 1) as 1 | 2 | 3;
    razones.push(`toca ${n} archivos (mucho contexto): sube un nivel`);
  }
  if (entrada.criticidad === 'alta') {
    if (nivel < 3) razones.push('criticidad alta (dinero, seguridad, datos): no se ahorra aquí');
    else razones.push('criticidad alta: el escalón fuerte entra en la cascada');
    nivel = 3;
  }
  if (entrada.privacidad === 'no_sale') razones.push('el contenido no puede salir de tu máquina: solo modelos locales');
  return { nivel, motivo: razones.join(' · ') };
}

export async function sugerirModelo(
  ctx: ServerCtx,
  entrada: {
    descripcion: string;
    tipo?: string;
    archivos_tocados?: number;
    criticidad?: 'baja' | 'media' | 'alta';
    privacidad?: 'puede_salir' | 'no_sale';
  },
): Promise<Sugerencia & { clasificacion: { nivel: number; motivo: string }; catalogo_usado: string }> {
  const catalogo = await leerCatalogo(ctx);
  const { nivel, motivo } = clasificarTarea(entrada);
  const advertencias: string[] = [];
  const ajustes: string[] = [];

  // Candidatos: los que alcanzan la fuerza requerida. En nivel 3 eso deja (a
  // propósito) solo los modelos fuertes: en lo crítico no se empieza barato.
  let candidatos = catalogo.filter((m) => m.fuerza >= nivel);
  if (entrada.privacidad === 'no_sale') {
    candidatos = catalogo.filter((m) => m.donde === 'local');
    if (candidatos.length === 0) advertencias.push('Pediste que el contenido no salga y no hay modelos locales en el catálogo: agrega uno o acepta el riesgo explícitamente.');
  }
  if (candidatos.length === 0) candidatos = catalogo.slice().sort((a, b) => b.fuerza - a.fuerza).slice(0, 1);

  // Ordena: primero lo más barato; en empate, el de menor fuerza (suficiente = mejor).
  candidatos.sort((a, b) => a.coste_relativo - b.coste_relativo || a.fuerza - b.fuerza);

  // Recalibración con datos reales del taller.
  const historial = historialPorModelo(ctx.db, entrada.tipo);
  for (const h of historial) {
    if (h.tareas < 3) continue;
    const tasa = h.exitos / h.tareas;
    const idx = candidatos.findIndex((c) => c.id === h.modelo);
    if (idx === -1) continue;
    if (tasa < 0.7) {
      const [m] = candidatos.splice(idx, 1);
      candidatos.push(m!);
      ajustes.push(`${m!.nombre} (${m!.id}) resolvió ${Math.round(tasa * 100)}% de las tareas de tipo "${entrada.tipo}" en tu historial: baja al final de la cascada.`);
    } else if (tasa >= 0.9 && idx > 0) {
      const [m] = candidatos.splice(idx, 1);
      candidatos.unshift(m!);
      ajustes.push(`${m!.nombre} (${m!.id}) resolvió ${Math.round(tasa * 100)}% de las tareas de tipo "${entrada.tipo}" con coste ${h.coste_total}: súbelo al frente.`);
    }
  }

  // Cascada: los dos escalones más baratos que alcanzan el nivel y, como último
  // recurso, el modelo más fuerte disponible (el objetivo de escalado).
  const elegidos: Modelo[] = candidatos.slice(0, 2);
  const fuerte = candidatos.filter((m) => m.fuerza === 3).sort((a, b) => a.coste_relativo - b.coste_relativo)[0];
  if (nivel >= 2 && fuerte && !elegidos.includes(fuerte)) elegidos.push(fuerte);
  else if (nivel >= 2 && candidatos[2] && !elegidos.includes(candidatos[2])) elegidos.push(candidatos[2]!);
  const cascada = elegidos.map((m, i) => ({
    orden: i + 1,
    modelo: m.nombre,
    id: m.id,
    por_que: i === 0
      ? `suficiente para nivel ${nivel} y el más barato de los candidatos (coste relativo ${m.coste_relativo})`
      : `escalón si el anterior no resuelve (fuerza ${m.fuerza}, coste ${m.coste_relativo})`,
  }));
  const costeMax = Math.max(...cascada.map((c) => catalogo.find((m) => m.id === c.id)?.coste_relativo ?? 1));

  return {
    nivel,
    motivo,
    cascada,
    presupuesto_sugerido: costeMax === 0
      ? 'sin coste por uso (todo local)'
      : `hasta ${costeMax} unidades relativas si se agota la cascada; registra el resultado para afinar`,
    regla_de_escalado: 'Si el primer modelo no resuelve en 2 intentos (o el parche empeora las pruebas), sube un escalón. Registra SIEMPRE el resultado con modelo_resultado.',
    ajustes_por_datos: ajustes,
    advertencias,
    clasificacion: { nivel, motivo },
    catalogo_usado: path.join(ctx.config.dataDir, 'ruter', 'modelos.json'),
  };
}

export function registrarResultado(
  ctx: ServerCtx,
  datos: { tarea: string; tipo: string; modelo: string; exito: boolean; coste?: number; duracion_ms?: number; evidencia?: string },
): void {
  ctx.db
    .prepare(
      `INSERT INTO costos_modelo (tarea, tipo, modelo, exito, coste, duracion_ms, evidencia, registrado_en, registrado_por)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
    .run(
      datos.tarea,
      datos.tipo,
      datos.modelo,
      datos.exito ? 1 : 0,
      datos.coste ?? 0,
      datos.duracion_ms ?? null,
      datos.evidencia ?? null,
      new Date().toISOString(),
      ctx.apiKey?.nombre ?? 'local',
    );
}

export interface Comparativa {
  por_modelo: {
    modelo: string;
    tareas: number;
    exitos: number;
    tasa_exito: number;
    coste_total: number;
    coste_por_exito: number | null;
  }[];
  recomendacion: string;
  muestra_suficiente: boolean;
}

/** Coste por RESULTADO ÚTIL: coste total ÷ tareas exitosas. */
export function compararCostes(ctx: ServerCtx, tipo?: string): Comparativa {
  const filas = historialPorModelo(ctx.db, tipo);
  const por_modelo = filas
    .map((f) => ({
      modelo: f.modelo,
      tareas: f.tareas,
      exitos: f.exitos,
      tasa_exito: f.tareas ? Number((f.exitos / f.tareas).toFixed(2)) : 0,
      coste_total: Number(f.coste_total.toFixed(2)),
      coste_por_exito: f.exitos > 0 ? Number((f.coste_total / f.exitos).toFixed(2)) : null,
    }))
    .sort((a, b) => (a.coste_por_exito ?? Infinity) - (b.coste_por_exito ?? Infinity));

  const conMuestra = por_modelo.filter((m) => m.tareas >= 3);
  let recomendacion: string;
  if (por_modelo.length === 0) {
    recomendacion = 'Aún no hay resultados registrados. Empieza a registrar con modelo_resultado: en pocos días tendrás "coste por tarea terminada" por modelo.';
  } else if (conMuestra.length === 0) {
    recomendacion = `Hay ${por_modelo.length} modelo(s) con datos, pero menos de 3 tareas cada uno: sigue registrando antes de decidir.`;
  } else {
    const mejor = conMuestra[0]!;
    recomendacion = `Hoy, el mejor coste por resultado útil es ${mejor.modelo}: ${mejor.coste_por_exito} unidades por tarea exitosa (${mejor.exitos}/${mejor.tareas} exitosas).`;
  }
  return { por_modelo, recomendacion, muestra_suficiente: conMuestra.length > 0 };
}

/* ─────────────────────────── Política por tipo ─────────────────────────── */

export interface PoliticaTipo {
  presupuesto_max?: number;
  modelos_preferidos?: string[];
  solo_local?: boolean;
  nota?: string;
}

export async function leerPolitica(ctx: ServerCtx): Promise<Record<string, PoliticaTipo>> {
  try {
    const crudo = JSON.parse(await fs.readFile(path.join(ctx.config.dataDir, 'ruter', 'politica.json'), 'utf8'));
    return crudo.tipos ?? {};
  } catch {
    return {};
  }
}

export async function guardarPolitica(ctx: ServerCtx, tipos: Record<string, PoliticaTipo>): Promise<string> {
  const dir = path.join(ctx.config.dataDir, 'ruter');
  await fs.mkdir(dir, { recursive: true });
  const archivo = path.join(dir, 'politica.json');
  await fs.writeFile(archivo, JSON.stringify({ tipos, actualizado: new Date().toISOString() }, null, 2), 'utf8');
  return archivo;
}

export function idTarea(): string {
  return `T-${crypto.randomBytes(3).toString('hex')}`;
}

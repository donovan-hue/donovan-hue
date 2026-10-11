/**
 * CENTINELA — extensiones de la fase 4C:
 *
 *   · auditarPermisos()  → deja de "avisar genérico": revisa CÓMO está protegida
 *     cada ruta (middleware en la línea, guard global antes de montarla, o
 *     indicios en el archivo) y solo marca lo que de verdad queda descubierto.
 *   · revisarDependenciasVulnerables() → avisos REALES vía `npm audit --json`,
 *     con caché local por contenido de package.json/lockfile y respaldo a la
 *     tabla local cuando no hay red.
 */
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import type { ServerCtx } from '../context.js';
import { AVISOS_DEPENDENCIAS } from './centinela.js';

const exec = promisify(execFile);

/* ─────────────────────────── Permisos de rutas ─────────────────────────── */

export type ProteccionRuta = 'middleware_en_linea' | 'guard_global' | 'indicio_en_archivo' | 'ninguna';

export interface RutaAuditada {
  archivo: string;
  linea: number;
  metodo: string;
  ruta: string;
  proteccion: ProteccionRuta;
  evidencia: string;
}

export interface AuditoriaPermisos {
  rutas: RutaAuditada[];
  resumen: Record<ProteccionRuta, number>;
  descubiertas: RutaAuditada[];
  recomendaciones: string[];
  nota: string;
}

const RE_RUTA = /(?:app|router|servidor|rutas?|routes?)\s*\.\s*(get|post|put|patch|delete|all|use)\s*\(\s*['"`]([^'"`]+)['"`]([^\n]*)/g;
const RE_GUARD_GLOBAL = /(?:app|router|servidor|rutas?|routes?)\s*\.\s*use\s*\(\s*(?:await\s+)?([A-Za-z_$][\w$.]*)/g;
const INDICIOS_AUTH = /req\.(?:user|session|usuario)|\b(?:requireAuth|autenticar|autorizar|verificarToken|validarToken|isAuthenticated|estaAutenticado|ensureAuth|requireLogin|checkAuth|authGuard|guard|passport|jwt\.verify|bearer|authorization|api_?key|apikey|verificarSesion)\b/i;
const MIDDLEWARE_EN_LINEA = /\b(?:requireAuth|autenticar|autorizar|verificarToken|validarToken|isAuthenticated|estaAutenticado|ensureAuth|requireLogin|checkAuth|authGuard|auth|guard|passport\.authenticate|verificarSesion)\b/i;
const ES_PUBLICA_A_PROPOSITO = /p[uú]blic|public|health|salud|login|callback|webhook|precios|catalogo_p[úu]blico/i;

const ES_CODIGO = /\.(ts|tsx|js|jsx|mjs|cjs)$/;
const ARCHIVOS_IGNORADOS = /(^|\/)(node_modules|dist|build|\.git|coverage)(\/|$)/;

async function archivosDeCodigo(raiz: string): Promise<string[]> {
  const salida: string[] = [];
  async function recorrer(dir: string): Promise<void> {
    let entradas;
    try {
      entradas = await fs.readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entradas) {
      const absoluto = path.join(dir, e.name);
      const rel = path.relative(raiz, absoluto).split(path.sep).join('/');
      if (ARCHIVOS_IGNORADOS.test(rel)) continue;
      if (e.isDirectory()) await recorrer(absoluto);
      else if (e.isFile() && ES_CODIGO.test(e.name)) salida.push(rel);
    }
  }
  await recorrer(raiz);
  return salida;
}

/**
 * Audita cómo está protegida cada ruta registrada en el proyecto.
 * Reglas (de la más fuerte a la más débil):
 *   1. middleware_en_linea → la propia llamada trae el guard (app.get('/x', requireAuth, ...))
 *   2. guard_global        → hay un `app.use(<guard>)` (con nombre de auth) ANTES de la ruta
 *   3. indicio_en_archivo  → el archivo consulta sesión/token en alguna parte (p. ej. dentro del handler)
 *   4. ninguna             → nada de lo anterior: queda por revisar
 */
export async function auditarPermisos(raiz: string): Promise<AuditoriaPermisos> {
  const archivos = await archivosDeCodigo(raiz);
  const rutas: RutaAuditada[] = [];

  for (const rel of archivos) {
    if (/\.test\.|\.spec\./.test(rel)) continue;
    let contenido: string;
    try {
      contenido = await fs.readFile(path.join(raiz, rel), 'utf8');
    } catch {
      continue;
    }
    const lineas = contenido.split('\n');
    const tieneIndicio = INDICIOS_AUTH.test(contenido);

    // Guards globales y en qué línea se montaron.
    const guardsGlobales: { linea: number; nombre: string }[] = [];
    for (let i = 0; i < lineas.length; i++) {
      for (const m of lineas[i]!.matchAll(RE_GUARD_GLOBAL)) {
        const nombre = m[1]!;
        if (MIDDLEWARE_EN_LINEA.test(nombre) || /guard|auth|protec/i.test(nombre)) {
          guardsGlobales.push({ linea: i + 1, nombre });
        }
      }
    }

    for (let i = 0; i < lineas.length; i++) {
      const linea = lineas[i]!;
      if (linea.length > 800) continue;
      const re = new RegExp(RE_RUTA.source, RE_RUTA.flags);
      let m: RegExpExecArray | null;
      while ((m = re.exec(linea)) !== null) {
        const [metodo, ruta, cola] = [m[1]!, m[2]!, m[3] ?? ''];
        if (metodo === 'use') continue; // los use son montajes/globales, no rutas finales
        let proteccion: ProteccionRuta = 'ninguna';
        let evidencia = linea.trim().slice(0, 140);

        if (MIDDLEWARE_EN_LINEA.test(cola)) {
          proteccion = 'middleware_en_linea';
          evidencia = `guard en la propia línea: ${cola.trim().slice(0, 100)}`;
        } else {
          const guardPrevio = guardsGlobales.find((g) => g.linea < i + 1);
          if (guardPrevio) {
            proteccion = 'guard_global';
            evidencia = `protegida por ${guardPrevio.nombre} (app.use en línea ${guardPrevio.linea})`;
          } else if (tieneIndicio) {
            proteccion = 'indicio_en_archivo';
            evidencia = 'el archivo verifica sesión/token (revisa que aplique a ESTA ruta)';
          }
        }
        rutas.push({ archivo: rel, linea: i + 1, metodo: metodo.toUpperCase(), ruta, proteccion, evidencia });
      }
    }
  }

  const resumen: Record<ProteccionRuta, number> = {
    middleware_en_linea: 0, guard_global: 0, indicio_en_archivo: 0, ninguna: 0,
  };
  for (const r of rutas) resumen[r.proteccion]++;

  const descubiertas = rutas.filter((r) => r.proteccion === 'ninguna' && !ES_PUBLICA_A_PROPOSITO.test(r.ruta));
  const publicasProbables = rutas.filter((r) => r.proteccion === 'ninguna' && ES_PUBLICA_A_PROPOSITO.test(r.ruta));

  const recomendaciones: string[] = [];
  for (const r of descubiertas.slice(0, 10)) {
    recomendaciones.push(`${r.archivo}:${r.linea} → ${r.metodo} ${r.ruta}: no encontré guard; agrega el middleware de autenticación o márcala como pública con un comentario.`);
  }
  if (publicasProbables.length) {
    recomendaciones.push(`Parecen públicas a propósito (${publicasProbables.map((p) => p.ruta).join(', ')}): confírmalo con un comentario o una lista explícita de rutas públicas.`);
  }

  return {
    rutas,
    resumen,
    descubiertas,
    recomendaciones,
    nota:
      'Análisis estático: reconoce los patrones más comunes (guard en la línea, app.use previo, consulta de sesión en el handler). Si usas un framework con protección implícita, agrégalo a .centinela-ignore con su motivo o documenta el patrón.',
  };
}

/* ─────────────────────── Dependencias vulnerables ─────────────────────── */

export interface AvisoReal {
  paquete: string;
  severidad: string;
  titulo: string;
  rango_vulnerable?: string;
  corrige?: string;
  url?: string;
}

export interface RevisionDependencias {
  fuente: 'npm-audit' | 'tabla-local';
  cacheada: boolean;
  generada_en: string;
  total: number;
  por_severidad: Record<string, number>;
  avisos: AvisoReal[];
  avisos_locales: { paquete: string; rango: string; aviso: string }[];
  notas: string[];
}

interface CacheAuditoria {
  clave: string;
  revisado_en: string;
  datos: Omit<RevisionDependencias, 'cacheada' | 'fuente'> & { fuente: RevisionDependencias['fuente'] };
}

function dirCentinela(ctx: ServerCtx): string {
  return path.join(ctx.config.dataDir, 'centinela');
}

/** Clave de caché: contenido de package.json + lockfile (si existe). */
async function claveProyecto(raiz: string): Promise<string> {
  const partes: string[] = [];
  for (const nombre of ['package.json', 'package-lock.json']) {
    try {
      const contenido = await fs.readFile(path.join(raiz, nombre), 'utf8');
      partes.push(`${nombre}:${crypto.createHash('sha256').update(contenido).digest('hex').slice(0, 16)}`);
    } catch {
      partes.push(`${nombre}:-`);
    }
  }
  return partes.join('|');
}

async function leerCache(ctx: ServerCtx, raiz: string): Promise<CacheAuditoria | null> {
  try {
    const crudo = JSON.parse(await fs.readFile(path.join(dirCentinela(ctx), 'audit-cache.json'), 'utf8')) as Record<string, CacheAuditoria>;
    return crudo[path.basename(raiz)] ?? null;
  } catch {
    return null;
  }
}

async function guardarCache(ctx: ServerCtx, raiz: string, entrada: CacheAuditoria): Promise<void> {
  const dir = dirCentinela(ctx);
  await fs.mkdir(dir, { recursive: true });
  const archivo = path.join(dir, 'audit-cache.json');
  let crudo: Record<string, CacheAuditoria> = {};
  try {
    crudo = JSON.parse(await fs.readFile(archivo, 'utf8'));
  } catch {
    /* primera vez */
  }
  crudo[path.basename(raiz)] = entrada;
  await fs.writeFile(archivo, JSON.stringify(crudo, null, 2), 'utf8');
}

const VIGENCIA_CACHE_MS = 24 * 60 * 60 * 1000;

/**
 * Recorta el primer objeto JSON balanceado de un texto que puede traer basura
 * alrededor (npm imprime JSON y, además, líneas de error en stderr). Sin esto,
 * un `JSON.parse` fallido se confundía con «cero vulnerabilidades».
 */
export function extraerObjetoJson(texto: string): string | null {
  const inicio = texto.indexOf('{');
  if (inicio < 0) return null;
  let profundidad = 0;
  let enCadena = false;
  let escapado = false;
  for (let i = inicio; i < texto.length; i += 1) {
    const c = texto[i]!;
    if (enCadena) {
      if (escapado) escapado = false;
      else if (c === '\\') escapado = true;
      else if (c === '"') enCadena = false;
      continue;
    }
    if (c === '"') enCadena = true;
    else if (c === '{') profundidad += 1;
    else if (c === '}') {
      profundidad -= 1;
      if (profundidad === 0) return texto.slice(inicio, i + 1);
    }
  }
  return null;
}

/** Interpreta la salida de `npm audit --json` (formatos v2 con vulnerabilities). */
export function interpretarAudit(salida: string): { total: number; porSeveridad: Record<string, number>; avisos: AvisoReal[] } {
  const avisos: AvisoReal[] = [];
  const porSeveridad: Record<string, number> = { critica: 0, alta: 0, media: 0, baja: 0, info: 0 };
  const mapa: Record<string, keyof typeof porSeveridad> = {
    critical: 'critica', high: 'alta', moderate: 'media', low: 'baja', info: 'info',
  };
  let datos: any;
  try {
    datos = JSON.parse(salida);
  } catch {
    return { total: -1, porSeveridad, avisos };
  }
  // npm devuelve JSON de error (p. ej. ENOLOCK sin lockfile): eso NO es "cero avisos".
  if (datos?.error || (!datos?.vulnerabilities && !datos?.metadata)) {
    return { total: -1, porSeveridad, avisos };
  }
  const vulnerabilidades = datos?.vulnerabilities ?? {};
  for (const [paquete, v] of Object.entries<any>(vulnerabilidades)) {
    const severidad = mapa[v?.severity] ?? 'media';
    severidad in porSeveridad ? (porSeveridad[severidad] += 1) : null;
    const via = Array.isArray(v?.via) ? v.via.find((x: any) => typeof x === 'object') : undefined;
    avisos.push({
      paquete,
      severidad,
      titulo: via?.title ?? v?.via?.find?.((x: any) => typeof x === 'string') ?? 'Aviso de seguridad',
      rango_vulnerable: v?.range,
      corrige: v?.fixAvailable === true ? 'hay actualización automática disponible' : typeof v?.fixAvailable === 'object' ? `actualiza a ${v.fixAvailable.version}` : undefined,
      url: via?.url,
    });
  }
  return { total: Object.keys(vulnerabilidades).length, porSeveridad, avisos };
}

/** Revisa dependencias con `npm audit` (con caché) y complementa con la tabla local. */
export async function revisarDependenciasVulnerables(
  ctx: ServerCtx,
  raiz: string,
  opciones: { forzar?: boolean; comando?: string } = {},
): Promise<RevisionDependencias> {
  const clave = await claveProyecto(raiz);
  const cache = await leerCache(ctx, raiz);
  if (!opciones.forzar && cache && cache.clave === clave && Date.now() - Date.parse(cache.revisado_en) < VIGENCIA_CACHE_MS) {
    return { ...cache.datos, cacheada: true } as RevisionDependencias;
  }

  let paquete: any = {};
  try {
    paquete = JSON.parse(await fs.readFile(path.join(raiz, 'package.json'), 'utf8'));
  } catch {
    throw new Error(`No encontré package.json en ${raiz}.`);
  }
  const todas: Record<string, string> = { ...(paquete.dependencies ?? {}), ...(paquete.devDependencies ?? {}) };
  const avisos_locales = AVISOS_DEPENDENCIAS
    .filter((a) => todas[a.paquete])
    .map((a) => ({ paquete: a.paquete, rango: todas[a.paquete]!, aviso: `${a.nota} (corrige: ${a.corrige})` }));

  const notas: string[] = [];
  if (!avisos_locales.length && Object.keys(todas).length) notas.push('Ninguna dependencia coincide con la tabla local de avisos frecuentes.');

  let fuente: RevisionDependencias['fuente'] = 'tabla-local';
  let total = 0;
  let por_severidad: Record<string, number> = { critica: 0, alta: 0, media: 0, baja: 0, info: 0 };
  let avisos: AvisoReal[] = [];

  const comando = opciones.comando ?? 'npm audit --json';
  try {
    const { stdout } = await exec('/bin/sh', ['-c', comando], {
      cwd: raiz,
      timeout: 60_000,
      maxBuffer: 8 * 1024 * 1024,
      env: { ...process.env, FORCE_COLOR: '0' },
    });
    const r = interpretarAudit(stdout);
    // El comando salió 0, pero el JSON puede ser un ERROR (p. ej. ENOLOCK): solo
    // vale como fuente real si trae reporte de vulnerabilidades de verdad.
    const datos = (() => { try { return JSON.parse(stdout); } catch { return {}; } })();
    if (r.total >= 0 && !datos?.error) {
      fuente = 'npm-audit';
      total = r.total;
      por_severidad = r.porSeveridad;
      avisos = r.avisos;
    } else {
      notas.push(
        `\`npm audit\` no pudo dar una respuesta completa (${String(datos?.error?.code ?? 'sin datos')}): se usó la tabla local de avisos frecuentes. Para avisos reales: npm i --package-lock-only y vuelve a consultar.`,
      );
    }
  } catch (e: any) {
    // npm audit sale con código != 0 cuando HAY vulnerabilidades: eso no es un fallo, trae datos.
    const salida = `${String(e?.stdout ?? '')}\n${String(e?.stderr ?? '')}`;
    // npm mezcla el JSON con líneas de error: hay que recortar SOLO el objeto balanceado.
    const json = extraerObjetoJson(salida);
    if (json) {
      const r = interpretarAudit(json);
      // El JSON puede ser un ERROR disfrazado (ENOLOCK, red): solo vale si trae datos de verdad.
      const datos = (() => { try { return JSON.parse(json); } catch { return {}; } })();
      if (r.total >= 0 && !datos?.error) {
        fuente = 'npm-audit';
        total = r.total;
        por_severidad = r.porSeveridad;
        avisos = r.avisos;
      } else {
        notas.push(
          `\`npm audit\` no pudo dar una respuesta completa (${String(datos?.error?.code ?? e?.message ?? e).slice(0, 80)}): se usó la tabla local de avisos frecuentes. Para avisos reales: npm i --package-lock-only y vuelve a consultar.`,
        );
      }
    } else {
      notas.push(
        `\`npm audit\` no pudo dar una respuesta completa (¿sin red o sin package-lock.json?): ${String(e?.message ?? e).slice(0, 160)}. Se usó la tabla local de avisos frecuentes.`,
      );
    }
  }

  const datos: RevisionDependencias = {
    fuente,
    cacheada: false,
    generada_en: new Date().toISOString(),
    total,
    por_severidad,
    avisos,
    avisos_locales,
    notas,
  };
  await guardarCache(ctx, raiz, { clave, revisado_en: datos.generada_en, datos });
  return datos;
}

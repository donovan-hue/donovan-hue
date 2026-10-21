/**
 * PARCHE (fase 4C) — reparar con red de seguridad.
 *
 * Flujo: diagnosticar una falla con evidencia → proponer un parche revisable
 * (diff + huella) → probarlo en una copia aislada corriendo las pruebas ANTES y
 * DESPUÉS → aplicarlo solo con aprobación humana y respaldo → revertirlo con un
 * comando si algo sale mal.
 *
 * Guardarraíles implementados aquí:
 *   · el repositorio real solo se toca en `aplicarParche` (y con respaldo previo);
 *   · `probarParche` trabaja siempre sobre una copia bajo data/parche/pruebas/;
 *   · límite de intentos por parche (PARCHE_MAX_INTENTOS, por defecto 3);
 *   · toda afirmación del diagnóstico lleva evidencia archivo:línea;
 *   · los secretos siguen redactándose (el diff de un parche no debe exponerlos).
 */
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import type { ServerCtx } from '../context.js';
import type { DB } from '../db/index.js';
import { analizarProyecto } from './grafo.js';

const exec = promisify(execFile);

export const MAX_INTENTOS = Math.max(1, Number(process.env.PARCHE_MAX_INTENTOS ?? 3));
export const TIMEOUT_PRUEBAS_MS = Math.max(5_000, Number(process.env.PARCHE_PRUEBA_TIMEOUT_MS ?? 120_000));

/* ───────────────────────────── Diagnóstico ───────────────────────────── */

export interface CausaProbable {
  causa: string;
  confianza: 'alta' | 'media' | 'baja';
  evidencia: string[];
  archivos: string[];
  sugerencia: string;
}

export interface Diagnostico {
  tipo: 'excepcion' | 'prueba' | 'log' | 'desconocido';
  resumen: string;
  sintomas: string[];
  causas: CausaProbable[];
  archivos_relacionados: { archivo: string; motivo: string }[];
  siguiente_paso: string;
}

interface Referencia {
  archivo: string;
  linea: number;
}

const RE_ARCHIVO_LINEA = /([\w@./\\-]+\.(?:ts|tsx|js|jsx|mjs|cjs|json|yml|yaml|sh))[:(](\d+)(?::\d+)?/g;
const RE_ERROR_TIPO = /^([A-Za-z]*Error|AssertionError)(?::\s*(.+))?$/m;
const RE_MODULO_FALTANTE = /Cannot find module '([^']+)'/;
const RE_ENOENT = /ENOENT[^']*'([^']+)'/;
const RE_EXPECTATIVA = /expected\s+(.+?)\s+to\s+(?:be|equal|deeply equal|contain|match)\s+(.+?)(?:$|\n)/i;
const RE_PRUEBA_FALLIDA = /(?:FAIL|✗|×|●)\s+([^\s>›]+)(?:\s*[>›]\s*(.+))?/;
/** Runner de pruebas de Node (formato TAP): "not ok 287 - nombre de la prueba". */
const RE_TAP_FALLIDA = /^\s*not ok\s+\d+\s*-\s*(.+)$/m;
/** Aserción que recibe null donde esperaba texto (assert.match / assert.equal). */
const RE_ASERCION_NULA = /must be of type string\. Received type object \(null\)|Received type object \(null\)|ERR_ASSERTION/;
const RE_ECONN = /ECONNREFUSED|ETIMEDOUT|socket hang up|EAI_AGAIN/;

/** Recorta la entrada a un tamaño razonable para analizar (logs pueden ser enormes). */
function recortar(entrada: string, max = 20_000): string {
  return entrada.length > max ? `${entrada.slice(0, max)}\n…(recortado)` : entrada;
}

/** Busca referencias archivo:línea dentro del texto de la falla. */
function extraerReferencias(entrada: string): Referencia[] {
  const salida: Referencia[] = [];
  const vistos = new Set<string>();
  for (const m of entrada.matchAll(RE_ARCHIVO_LINEA)) {
    const archivo = m[1]!.replace(/^.*?(?=(?:src|test|tests|app|lib|packages)\/)/, '');
    const clave = `${archivo}:${m[2]}`;
    if (vistos.has(clave)) continue;
    vistos.add(clave);
    salida.push({ archivo, linea: Number(m[2]) });
  }
  return salida.slice(0, 12);
}

/**
 * Resuelve el archivo de una referencia: primero tal cual y, si no está,
 * buscando por terminación de ruta dentro del proyecto. Hace falta porque la
 * ruta del error suele venir recortada (p. ej. `test/x.test.js` cuando el
 * archivo real es `server/test/x.test.js` en un monorepo).
 */
async function resolverArchivo(raiz: string, rel: string): Promise<string | null> {
  if (await existe(raiz, rel)) return rel;
  const ignorar = new Set(['node_modules', '.git', 'dist', 'coverage', '.next', 'build', 'vendor', '.venv', '__pycache__']);
  const partes = rel.split('/').filter(Boolean);
  const encontrados: string[] = [];
  async function caminar(dir: string, profundidad: number): Promise<void> {
    if (profundidad > 6 || encontrados.length > 4) return;
    let entradas: import('node:fs').Dirent[];
    try {
      entradas = await fs.readdir(path.join(raiz, dir), { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entradas) {
      if (ignorar.has(e.name)) continue;
      const relHijo = dir ? `${dir}/${e.name}` : e.name;
      if (e.isDirectory()) await caminar(relHijo, profundidad + 1);
      else if (e.isFile() && relHijo.split('/').slice(-partes.length).join('/') === rel) encontrados.push(relHijo);
    }
  }
  await caminar('', 0);
  encontrados.sort((a, b) => a.length - b.length);
  return encontrados[0] ?? null;
}

async function leerContexto(raiz: string, ref: Referencia, margen = 2): Promise<string[]> {
  const encontrado = await resolverArchivo(raiz, ref.archivo);
  if (!encontrado) return [`(no se pudo leer ${ref.archivo} dentro del proyecto)`];
  try {
    const contenido = await fs.readFile(path.join(raiz, encontrado), 'utf8');
    const lineas = contenido.split('\n');
    const salida: string[] = [];
    // La línea referida puede no existir ya (código editado después del volcado):
    // en ese caso se muestran las últimas líneas y se dice explícitamente.
    if (ref.linea > lineas.length) {
      const desdeFinal = Math.max(0, lineas.length - 2 * margen - 1);
      for (let i = desdeFinal; i < lineas.length; i++) salida.push(`  ${encontrado}:${i + 1}: ${lineas[i]!.trim().slice(0, 160)}`);
      salida.push(`(la línea ${ref.linea} referida no existe en este archivo; se muestran las últimas ${salida.length} líneas)`);
      return salida;
    }
    const desde = Math.max(0, ref.linea - 1 - margen);
    const hasta = Math.min(lineas.length, ref.linea + margen);
    for (let i = desde; i < hasta; i++) {
      const marca = i === ref.linea - 1 ? '→' : ' ';
      salida.push(`${marca} ${encontrado}:${i + 1}: ${lineas[i]!.trim().slice(0, 160)}`);
    }
    return salida;
  } catch {
    return [`(no se pudo leer ${ref.archivo} dentro del proyecto)`];
  }
}

async function existe(raiz: string, rel: string): Promise<boolean> {
  return fs
    .access(path.join(raiz, rel))
    .then(() => true)
    .catch(() => false);
}

/** Diagnostica una falla (error, log o prueba rota) con evidencia archivo:línea. */
export async function diagnosticar(raiz: string, entradaCruda: string): Promise<Diagnostico> {
  const entrada = recortar(entradaCruda);
  const referencias = extraerReferencias(entrada);
  const sintomas: string[] = [];
  const causas: CausaProbable[] = [];

  const tipoError = RE_ERROR_TIPO.exec(entrada);
  const moduloFaltante = RE_MODULO_FALTANTE.exec(entrada);
  const enoent = RE_ENOENT.exec(entrada);
  const expectativa = RE_EXPECTATIVA.exec(entrada);
  const pruebaFallida = RE_PRUEBA_FALLIDA.exec(entrada);
  const pruebaFallidaTap = RE_TAP_FALLIDA.exec(entrada);
  const problemaRed = RE_ECONN.test(entrada);

  const esPrueba = Boolean(pruebaFallida) || Boolean(pruebaFallidaTap) || /Tests?\s+\d+\s+failed|FAIL\b/.test(entrada);
  const tipo: Diagnostico['tipo'] = esPrueba ? 'prueba' : tipoError ? 'excepcion' : /^\s*\{?"?t"?\s*:/m.test(entrada) ? 'log' : 'desconocido';

  if (tipoError) sintomas.push(`Error reportado: ${tipoError[1]}${tipoError[2] ? ` — ${tipoError[2].slice(0, 140)}` : ''}`);
  if (pruebaFallida) sintomas.push(`Prueba fallida: ${pruebaFallida[1]}${pruebaFallida[2] ? ` › ${pruebaFallida[2].slice(0, 100)}` : ''}`);
  if (!pruebaFallida && pruebaFallidaTap) sintomas.push(`Prueba fallida (runner de Node): ${pruebaFallidaTap[1]!.slice(0, 120)}`);
  if (expectativa) sintomas.push(`Expectativa incumplida: esperaba ${expectativa[1]!.trim().slice(0, 60)} · obtuvo ${expectativa[2]!.trim().slice(0, 60)}`);
  if (referencias.length) sintomas.push(`Referencias de código: ${referencias.slice(0, 4).map((r) => `${r.archivo}:${r.linea}`).join(', ')}`);

  // ── Causa: módulo no encontrado (ruta relativa rota o dependencia ausente) ──
  if (moduloFaltante) {
    const objetivo = moduloFaltante[1]!;
    if (objetivo.startsWith('.')) {
      const base = referencias[0] ? path.dirname(referencias[0].archivo) : '.';
      const candidatos = ['', '.js', '.ts', '/index.js', '/index.ts'].map((suf) => path.normalize(path.join(base, objetivo + suf)));
      const hallados: string[] = [];
      for (const c of candidatos) if (await existe(raiz, c)) hallados.push(c);
      causas.push({
        causa: `La ruta relativa '${objetivo}' no resuelve a ningún archivo`,
        confianza: hallados.length ? 'alta' : 'media',
        evidencia: [`import desde ${base}`, ...hallados.map((h) => `existe: ${h}`)],
        archivos: referencias.map((r) => r.archivo),
        sugerencia: hallados.length
          ? `Corrige el import para apuntar a '${hallados[0]}' (o renombra el archivo para que coincida).`
          : `Crea el archivo faltante o corrige la ruta del import; revisa mayúsculas y la extensión.`,
      });
    } else {
      let declarada = false;
      try {
        const pkg = JSON.parse(await fs.readFile(path.join(raiz, 'package.json'), 'utf8'));
        declarada = Boolean(pkg.dependencies?.[objetivo] ?? pkg.devDependencies?.[objetivo]);
      } catch {
        /* sin package.json */
      }
      causas.push({
        causa: declarada
          ? `'${objetivo}' está declarada pero no instalada (falta npm install)`
          : `'${objetivo}' se usa pero no está declarada en package.json`,
        confianza: 'alta',
        evidencia: [`Cannot find module '${objetivo}'`, declarada ? 'aparece en package.json' : 'no aparece en dependencies ni devDependencies'],
        archivos: referencias.map((r) => r.archivo),
        sugerencia: declarada ? 'Ejecuta npm install (o npm ci) y vuelve a intentar.' : `Instala y declara la dependencia: npm install ${objetivo}`,
      });
    }
  }

  // ── Causa: archivo/ruta inexistente (ENOENT) ──
  if (enoent) {
    const rutaFaltante = enoent[1]!;
    const relativa = path.isAbsolute(rutaFaltante) ? rutaFaltante : rutaFaltante;
    causas.push({
      causa: `Se intenta abrir algo que no existe: ${relativa}`,
      confianza: 'alta',
      evidencia: [`ENOENT: ${rutaFaltante}`, ...(referencias[0] ? await leerContexto(raiz, referencias[0]) : [])],
      archivos: referencias.map((r) => r.archivo),
      sugerencia: 'Verifica la ruta (¿está definida por variable de entorno?), crea el archivo/directorio o valida antes de leerlo.',
    });
  }

  // ── Causa: acceso a propiedad de undefined/null ──
  if (/Cannot read propert(?:y|ies) of (?:undefined|null)|is not a function|of undefined/.test(entrada)) {
    const ref = referencias[0];
    causas.push({
      causa: ref ? `Se accede a una propiedad de un valor vacío (undefined/null), señalado en ${ref.archivo}:${ref.linea}` : 'Se accede a una propiedad de un valor undefined/null',
      confianza: ref ? 'alta' : 'media',
      evidencia: ref ? await leerContexto(raiz, ref) : [entrada.split('\n')[0] ?? ''],
      archivos: referencias.map((r) => r.archivo),
      sugerencia: 'Comprueba el origen del valor (¿respuesta vacía?, ¿campo opcional?, ¿inicialización tardía?) y añade la guarda o el valor por defecto antes de usarlo.',
    });
  }

  // ── Causa: expectativa de prueba incumplida ──
  if (expectativa) {
    const archivoPrueba = referencias.find((r) => /\.test\.|\.spec\./.test(r.archivo));
    const evidencia = archivoPrueba ? await leerContexto(raiz, archivoPrueba) : [];
    causas.push({
      causa: archivoPrueba
        ? `La expectativa de ${archivoPrueba.archivo} no coincide con la implementación actual`
        : 'El resultado real difiere del esperado por la prueba',
      confianza: 'media',
      evidencia: [`esperado: ${expectativa[1]!.trim().slice(0, 80)}`, `obtenido: ${expectativa[2]!.trim().slice(0, 80)}`, ...evidencia],
      archivos: referencias.map((r) => r.archivo),
      sugerencia: 'Decide cuál es la verdad: si el comportamiento correcto es el que espera la prueba, ajusta la implementación; si el comportamiento cambió a propósito, actualiza la prueba — y dilo en el mensaje del commit.',
    });
  }

  // ── Causa: red/servicio caído ──
  if (problemaRed) {
    causas.push({
      causa: 'No se pudo conectar con un servicio externo (conexión rechazada, sin ruta o expiró)',
      confianza: 'alta',
      evidencia: [entrada.match(RE_ECONN)![0], ...referencias.slice(0, 2).map((r) => `${r.archivo}:${r.linea}`)],
      archivos: referencias.map((r) => r.archivo),
      sugerencia: 'Levanta o revisa el servicio (URL/puerto), agrega reintentos con espera y, si es crítico, un mensaje de error claro en lugar de una excepción cruda.',
    });
  }

  // ── Causa: error de sintaxis o tipos ──
  if (/SyntaxError|Unexpected token|Unexpected end of/.test(entrada)) {
    const ref = referencias[0];
    causas.push({
      causa: ref ? `Error de sintaxis cerca de ${ref.archivo}:${ref.linea}` : 'Error de sintaxis en el código',
      confianza: ref ? 'alta' : 'baja',
      evidencia: ref ? await leerContexto(raiz, ref, 3) : [entrada.split('\n')[0] ?? ''],
      archivos: referencias.map((r) => r.archivo),
      sugerencia: 'Revisa la línea indicada: llave/paréntesis sin cerrar, coma sobrante o una edición a medias.',
    });
  }

  // ── Causa: una comparación recibió null donde esperaba texto ──
  if (RE_ASERCION_NULA.test(entrada)) {
    const ref = referencias[0];
    causas.push({
      causa: ref
        ? `Una comparación recibió null donde esperaba texto (aserción señalada en ${ref.archivo}:${ref.linea})`
        : 'Una comparación recibió null donde esperaba texto (aserción de Node)',
      confianza: ref ? 'alta' : 'media',
      evidencia: [
        ...(ref ? await leerContexto(raiz, ref) : []),
        'La aserción falló porque el valor comparado es null, no porque el texto sea distinto.',
      ],
      archivos: referencias.map((r) => r.archivo),
      sugerencia: 'Averigua quién produce ese valor: si viene de una respuesta HTTP, revisa el handler de esa ruta (¿responde sin cuerpo o sin content-type?); si es un campo, valida antes de comparar. Corrige la causa, no la comparación.',
    });
  }

  if (causas.length === 0) {
    causas.push({
      causa: 'No pude identificar una causa única con lo recibido; falta contexto',
      confianza: 'baja',
      evidencia: entrada.split('\n').filter(Boolean).slice(0, 3),
      archivos: referencias.map((r) => r.archivo),
      sugerencia: 'Pega el error completo (con la traza), el nombre de la prueba que falla y el comando exacto con el que reproduces. Con eso vuelvo a diagnosticar.',
    });
  }

  // ── Archivos relacionados (importadores directos, vía GEMELO) ──
  const archivos_relacionados: { archivo: string; motivo: string }[] = [];
  if (referencias.length) {
    try {
      const grafo = await analizarProyecto(raiz);
      const objetivo = referencias[0]!.archivo;
      // importadoPor: destino → conjunto de archivos que lo importan.
      for (const [destino, importadores] of grafo.importadoPor) {
        if (destino === objetivo || destino.endsWith(`/${objetivo}`) || objetivo.endsWith(`/${destino}`)) {
          for (const quien of importadores) archivos_relacionados.push({ archivo: quien, motivo: `importa ${destino}` });
        }
      }
    } catch {
      /* proyecto sin grafo analizable */
    }
  }

  return {
    tipo,
    resumen: sintomas[0] ?? 'Falla sin un patrón reconocible a primera vista',
    sintomas,
    causas,
    archivos_relacionados: archivos_relacionados.slice(0, 10),
    siguiente_paso: 'Propón el parche con `parche_proponer` (los cambios deben decir qué texto exacto se sustituye por cuál) y pruébalo después con `parche_probar`.',
  };
}

/* ─────────────────────────────── Parche ─────────────────────────────── */

export interface CambioArchivo {
  ruta: string;
  /** Texto exacto que debe existir hoy (si no coincide, el parche está obsoleto). */
  antes: string;
  /** Texto que lo sustituye. */
  despues: string;
  descripcion?: string;
}

export interface ResultadoPruebas {
  comando: string;
  codigo: number;
  duracion_ms: number;
  ok: boolean;
  aprobadas?: number;
  fallidas?: number;
  salida: string;
}

export interface PruebaParche {
  antes: ResultadoPruebas;
  despues: ResultadoPruebas;
  veredicto: 'arregla' | 'mejora' | 'igual' | 'empeora';
  resumen: string;
  copia: string;
}

export interface Parche {
  id: string;
  proyecto: string;
  raiz: string;
  titulo: string;
  justificacion: string;
  cambios: CambioArchivo[];
  diff: string;
  huella: string;
  estado: 'propuesto' | 'probado' | 'aplicado' | 'revertido';
  creado_en: string;
  creado_por: string;
  intentos: number;
  prueba?: PruebaParche;
  aplicado_en?: string;
  respaldo?: string;
  revertido_en?: string;
}

interface FilaParche {
  id: string;
  proyecto: string;
  raiz: string;
  titulo: string;
  justificacion: string;
  diff: string;
  cambios: string;
  huella: string;
  estado: Parche['estado'];
  creado_en: string;
  creado_por: string;
  intentos: number;
  prueba: string | null;
  aplicado_en: string | null;
  respaldo: string | null;
  revertido_en: string | null;
}

function aParche(fila: FilaParche): Parche {
  return {
    id: fila.id,
    proyecto: fila.proyecto,
    raiz: fila.raiz,
    titulo: fila.titulo,
    justificacion: fila.justificacion,
    cambios: JSON.parse(fila.cambios) as CambioArchivo[],
    diff: fila.diff,
    huella: fila.huella,
    estado: fila.estado,
    creado_en: fila.creado_en,
    creado_por: fila.creado_por,
    intentos: fila.intentos,
    prueba: fila.prueba ? (JSON.parse(fila.prueba) as PruebaParche) : undefined,
    aplicado_en: fila.aplicado_en ?? undefined,
    respaldo: fila.respaldo ?? undefined,
    revertido_en: fila.revertido_en ?? undefined,
  };
}

/** Diff unificado mínimo (LCS por líneas, 3 de contexto) — legible para revisión. */
export function construirDiff(cambios: CambioArchivo[]): string {
  const lineas: string[] = [];
  for (const cambio of cambios) {
    lineas.push(`--- a/${cambio.ruta}`, `+++ b/${cambio.ruta}`);
    if (cambio.descripcion) lineas.push(`# ${cambio.descripcion}`);
    const A = cambio.antes.split('\n');
    const B = cambio.despues.split('\n');
    // LCS para saber qué líneas son comunes, eliminadas y añadidas.
    const n = A.length;
    const m = B.length;
    const tabla: number[][] = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
    for (let i = n - 1; i >= 0; i--) {
      for (let j = m - 1; j >= 0; j--) {
        tabla[i]![j] = A[i] === B[j] ? tabla[i + 1]![j + 1]! + 1 : Math.max(tabla[i + 1]![j]!, tabla[i]![j + 1]!);
      }
    }
    let i = 0;
    let j = 0;
    while (i < n && j < m) {
      if (A[i] === B[j]) {
        lineas.push(`  ${A[i]}`);
        i++;
        j++;
      } else if (tabla[i + 1]![j]! >= tabla[i]![j + 1]!) {
        lineas.push(`- ${A[i]}`);
        i++;
      } else {
        lineas.push(`+ ${B[j]}`);
        j++;
      }
    }
    for (; i < n; i++) lineas.push(`- ${A[i]}`);
    for (; j < m; j++) lineas.push(`+ ${B[j]}`);
    lineas.push('');
  }
  return lineas.join('\n');
}

export function huellaParche(diff: string, archivos: string[]): string {
  return crypto.createHash('sha256').update(`${archivos.sort().join('|')}\n${diff}`).digest('hex').slice(0, 16);
}

/** Validación: cada bloque «antes» debe existir exactamente una vez en su archivo. */
export async function validarCambios(raiz: string, cambios: CambioArchivo[]): Promise<string[]> {
  const problemas: string[] = [];
  if (cambios.length === 0) problemas.push('El parche no trae ningún cambio.');
  for (const c of cambios) {
    if (path.isAbsolute(c.ruta) || c.ruta.includes('..')) {
      problemas.push(`${c.ruta}: la ruta debe ser relativa y quedarse dentro del proyecto.`);
      continue;
    }
    let contenido: string;
    try {
      contenido = await fs.readFile(path.join(raiz, c.ruta), 'utf8');
    } catch {
      problemas.push(`${c.ruta}: el archivo no existe.`);
      continue;
    }
    const veces = contenido.split(c.antes).length - 1;
    if (veces === 0) problemas.push(`${c.ruta}: el bloque «antes» no aparece en el archivo (el parche está obsoleto o el texto no es exacto).`);
    else if (veces > 1) problemas.push(`${c.ruta}: el bloque «antes» aparece ${veces} veces; añade más contexto para que sea único.`);
    if (c.antes === c.despues) problemas.push(`${c.ruta}: el cambio no cambia nada.`);
  }
  return problemas;
}

/** Crea (sin guardar) un parche validado: diff, huella y metadatos. */
export async function crearParche(
  raiz: string,
  datos: { titulo: string; justificacion?: string; cambios: CambioArchivo[]; creado_por?: string },
): Promise<Parche> {
  const problemas = await validarCambios(raiz, datos.cambios);
  if (problemas.length) throw new Error(`No se puede proponer el parche:\n- ${problemas.join('\n- ')}`);
  const diff = construirDiff(datos.cambios);
  return {
    id: `PR-${crypto.randomBytes(3).toString('hex')}`,
    proyecto: path.basename(raiz),
    raiz,
    titulo: datos.titulo,
    justificacion: datos.justificacion ?? '',
    cambios: datos.cambios,
    diff,
    huella: huellaParche(diff, datos.cambios.map((c) => c.ruta)),
    estado: 'propuesto',
    creado_en: new Date().toISOString(),
    creado_por: datos.creado_por ?? 'local',
    intentos: 0,
  };
}

export function guardarParche(db: DB, parche: Parche): void {
  db.prepare(
    `INSERT INTO parches (id, proyecto, raiz, titulo, justificacion, diff, cambios, huella, estado, creado_en, creado_por, intentos, prueba, aplicado_en, respaldo, revertido_en)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(
    parche.id, parche.proyecto, parche.raiz, parche.titulo, parche.justificacion, parche.diff,
    JSON.stringify(parche.cambios), parche.huella, parche.estado, parche.creado_en, parche.creado_por,
    parche.intentos, parche.prueba ? JSON.stringify(parche.prueba) : null,
    parche.aplicado_en ?? null, parche.respaldo ?? null, parche.revertido_en ?? null,
  );
}

export function obtenerParche(db: DB, id: string): Parche | undefined {
  const fila = db.prepare('SELECT * FROM parches WHERE id = ?').get(id) as FilaParche | undefined;
  return fila ? aParche(fila) : undefined;
}

export function listarParches(db: DB, opciones: { limite?: number; estado?: Parche['estado'] } = {}): Parche[] {
  const filas = opciones.estado
    ? (db.prepare('SELECT * FROM parches WHERE estado = ? ORDER BY creado_en DESC LIMIT ?').all(opciones.estado, opciones.limite ?? 30) as FilaParche[])
    : (db.prepare('SELECT * FROM parches ORDER BY creado_en DESC LIMIT ?').all(opciones.limite ?? 30) as FilaParche[]);
  return filas.map(aParche);
}

export function actualizarParche(db: DB, id: string, cambios: Partial<Record<keyof FilaParche, unknown>>): void {
  const llaves = Object.keys(cambios);
  if (!llaves.length) return;
  const sets = llaves.map((k) => `${k} = ?`).join(', ');
  db.prepare(`UPDATE parches SET ${sets} WHERE id = ?`).run(...llaves.map((k) => cambios[k as keyof FilaParche] as unknown), id);
}

/* ─────────────────── Pruebas y copia aislada ─────────────────── */

/** Ejecuta el comando de pruebas y resume el resultado (aprobadas/fallidas si se pueden leer). */
export async function ejecutarPruebas(
  cwd: string,
  opciones: { comando?: string; timeoutMs?: number } = {},
): Promise<ResultadoPruebas> {
  let comando = opciones.comando;
  if (!comando) {
    try {
      const pkg = JSON.parse(await fs.readFile(path.join(cwd, 'package.json'), 'utf8'));
      if (pkg.scripts?.test) comando = 'npm test --silent';
    } catch {
      /* sin package.json */
    }
  }
  if (!comando) throw new Error('No hay comando de pruebas: pasa "comando" o define el script "test" en package.json.');

  const inicio = Date.now();
  let salida = '';
  let codigo = 0;
  try {
    const r = await exec('/bin/sh', ['-c', comando], {
      cwd,
      timeout: opciones.timeoutMs ?? TIMEOUT_PRUEBAS_MS,
      maxBuffer: 4 * 1024 * 1024,
      env: { ...process.env, CI: '1', FORCE_COLOR: '0' },
    });
    salida = `${r.stdout}\n${r.stderr}`;
  } catch (e: any) {
    codigo = typeof e?.code === 'number' ? e.code : 1;
    salida = `${e?.stdout ?? ''}\n${e?.stderr ?? ''}\n${e?.message ?? ''}`;
    if (e?.killed) salida += '\n(el comando excedió el tiempo límite)';
  }
  const aprobadas = Number(/Tests\s+(?:\d+\s+failed\s*\|\s*)?(\d+)\s+passed/.exec(salida)?.[1] ?? /(\d+)\s+passing/.exec(salida)?.[1] ?? NaN);
  const fallidas = Number(/(\d+)\s+failed/.exec(salida)?.[1] ?? NaN);
  const cola = salida.trim().split('\n').slice(-60).join('\n');
  return {
    comando,
    codigo,
    duracion_ms: Date.now() - inicio,
    ok: codigo === 0,
    aprobadas: Number.isFinite(aprobadas) ? aprobadas : undefined,
    fallidas: Number.isFinite(fallidas) ? fallidas : undefined,
    salida: cola,
  };
}

/** Copia el proyecto a un directorio aislado (sin node_modules; se enlaza si existe). */
export async function copiarProyecto(raiz: string, destino: string): Promise<void> {
  const ignorar = new Set(['node_modules', '.git', 'dist', 'coverage', '.next', 'build']);
  await fs.mkdir(destino, { recursive: true });
  async function copiar(rel: string): Promise<void> {
    const origen = path.join(raiz, rel);
    const entradas = await fs.readdir(origen, { withFileTypes: true });
    for (const e of entradas) {
      if (ignorar.has(e.name)) continue;
      const relHijo = rel ? path.join(rel, e.name) : e.name;
      const destinoHijo = path.join(destino, relHijo);
      if (e.isDirectory()) {
        await fs.mkdir(destinoHijo, { recursive: true });
        await copiar(relHijo);
      } else if (e.isFile()) {
        await fs.copyFile(path.join(origen, e.name), destinoHijo);
      }
    }
  }
  await copiar('');
  const nm = path.join(raiz, 'node_modules');
  const nmExiste = await fs.access(nm).then(() => true).catch(() => false);
  if (nmExiste) await fs.symlink(nm, path.join(destino, 'node_modules')).catch(() => {});
}

/** Aplica cambios dentro de un proyecto (copia o real), verificando que el «antes» siga ahí. */
export async function aplicarCambios(raiz: string, cambios: CambioArchivo[]): Promise<string[]> {
  const problemas = await validarCambios(raiz, cambios);
  if (problemas.length) throw new Error(`Los cambios ya no aplican:\n- ${problemas.join('\n- ')}`);
  for (const c of cambios) {
    const ruta = path.join(raiz, c.ruta);
    const contenido = await fs.readFile(ruta, 'utf8');
    await fs.writeFile(ruta, contenido.replace(c.antes, c.despues), 'utf8');
  }
  return cambios.map((c) => c.ruta);
}

/** Prueba un parche EN COPIAS aisladas: pruebas antes, cambios, pruebas después. */
export async function probarParche(
  ctx: ServerCtx,
  parche: Parche,
  opciones: { comando?: string; timeoutMs?: number } = {},
): Promise<PruebaParche> {
  const dirCopia = path.join(ctx.config.dataDir, 'parche', 'pruebas', parche.id);
  await fs.rm(dirCopia, { recursive: true, force: true });
  await fs.mkdir(dirCopia, { recursive: true });

  const copiaAntes = path.join(dirCopia, 'antes');
  const copiaDespues = path.join(dirCopia, 'despues');
  await copiarProyecto(parche.raiz, copiaAntes);
  await copiarProyecto(parche.raiz, copiaDespues);

  const antes = await ejecutarPruebas(copiaAntes, opciones);
  await aplicarCambios(copiaDespues, parche.cambios);
  const despues = await ejecutarPruebas(copiaDespues, opciones);

  const veredicto: PruebaParche['veredicto'] = antes.ok && despues.ok
    ? 'igual'
    : !antes.ok && despues.ok
      ? 'arregla'
      : antes.ok && !despues.ok
        ? 'empeora'
        : (despues.fallidas ?? Infinity) < (antes.fallidas ?? Infinity)
          ? 'mejora'
          : 'igual';
  const resumen =
    veredicto === 'arregla'
      ? `Arregla la falla: las pruebas pasan de rojo a verde (fallidas: ${antes.fallidas ?? 'n/d'} → ${despues.fallidas ?? 0}).`
      : veredicto === 'mejora'
        ? `Mejora parcial: fallidas ${antes.fallidas ?? '?'} → ${despues.fallidas ?? '?'}; aún no queda todo en verde.`
        : veredicto === 'empeora'
          ? `EMPEORA: las pruebas pasaban y ahora fallan (${despues.fallidas ?? '?'} fallidas). No aplicar.`
          : 'Sin cambio demostrable en las pruebas.';
  return { antes, despues, veredicto, resumen, copia: copiaDespues };
}

/* ─────────────────── Aplicar y revertir (con respaldo) ─────────────────── */

export async function aplicarParcheReal(ctx: ServerCtx, parche: Parche): Promise<{ archivos: string[]; respaldo: string }> {
  const respaldo = path.join(ctx.config.dataDir, 'parche', 'respaldos', parche.id);
  await fs.mkdir(respaldo, { recursive: true });
  for (const c of parche.cambios) {
    const destino = path.join(respaldo, c.ruta);
    await fs.mkdir(path.dirname(destino), { recursive: true });
    await fs.copyFile(path.join(parche.raiz, c.ruta), destino);
  }
  const archivos = await aplicarCambios(parche.raiz, parche.cambios);
  return { archivos, respaldo };
}

/**
 * Deshace un parche aplicado: restaura los archivos, marca el estado y deja el
 * rastro en la auditoría. Lo usan el tool MCP y la CLI (un solo camino, un solo
 * comportamiento: deshacer nunca pide aprobación).
 */
export async function deshacerParche(
  ctx: ServerCtx,
  parche: Parche,
  opciones: { herramienta?: string } = {},
): Promise<{ archivos_restaurados: string[] }> {
  if (parche.estado !== 'aplicado') {
    throw new Error(`El parche ${parche.id} está "${parche.estado}": solo se puede revertir lo aplicado.`);
  }
  const restaurados = await revertirParcheReal(ctx, parche);
  actualizarParche(ctx.db, parche.id, { estado: 'revertido', revertido_en: new Date().toISOString() });
  await ctx.audit.registrar({
    tipo: 'parche_revertido',
    cliente: ctx.apiKey?.nombre ?? 'local',
    herramienta: opciones.herramienta ?? 'reparacion_revertir',
    detalle: `${parche.id} · ${restaurados.join(', ')}`,
  });
  return { archivos_restaurados: [...new Set(restaurados)] };
}

export async function revertirParcheReal(ctx: ServerCtx, parche: Parche): Promise<string[]> {
  if (!parche.respaldo) throw new Error(`El parche ${parche.id} no tiene respaldo; no puedo revertirlo.`);
  const restaurados: string[] = [];
  for (const c of parche.cambios) {
    const origen = path.join(parche.respaldo, c.ruta);
    await fs.copyFile(origen, path.join(parche.raiz, c.ruta));
    restaurados.push(c.ruta);
  }
  return restaurados;
}

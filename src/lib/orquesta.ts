/**
 * ORQUESTA (fase 4D) — automatización de procesos completos, con aprobación.
 *
 * Cinco flujos de batalla probada + flujos propios definidos por el usuario.
 * Todo local: los pasos solo tocan el proyecto autorizado (lectura/git/health) o
 * escriben dentro de data/ (respaldos, informes, bitácora de corridas).
 *
 * Reglas:
 *   · cada corrida deja un acta JSON en data/orquesta/runs/ (qué paso, cuánto tardó, qué salió);
 *   · los pasos destructivos no existen como tales: lo que requiere permiso se
 *     propone (p. ej. el release) y se aplica con el gate de aprobación;
 *   · dry-run disponible para ver el plan sin ejecutar nada.
 */
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import type { ServerCtx } from '../context.js';
import { crearBackup } from './backup.js';
import { escanear } from './centinela.js';
import { analizarProyecto } from './grafo.js';

const exec = promisify(execFile);

export interface ResultadoPaso {
  paso: string;
  tipo: string;
  ok: boolean;
  ms: number;
  resumen: string;
  datos?: unknown;
  error?: string;
}

export interface ResultadoFlujo {
  flujo: string;
  nombre: string;
  dry_run: boolean;
  ok: boolean;
  inicio: string;
  duracion_ms: number;
  pasos: ResultadoPaso[];
  acta?: string;
}

export type TipoPaso =
  | 'estado_git'
  | 'salud_http'
  | 'respaldo'
  | 'seguridad_resumen'
  | 'dependencias_resumen'
  | 'pendientes_resumen'
  | 'release_propuesta'
  | 'post_mortem'
  | 'espera';

export const TIPOS_PERMITIDOS: TipoPaso[] = [
  'estado_git', 'salud_http', 'respaldo', 'seguridad_resumen',
  'dependencias_resumen', 'pendientes_resumen', 'release_propuesta', 'post_mortem', 'espera',
];

export interface DefinicionPaso {
  tipo: TipoPaso;
  nombre?: string;
  parametros?: Record<string, unknown>;
}

export interface FlujoDefinicion {
  id: string;
  nombre: string;
  descripcion: string;
  predefinido: boolean;
  pasos: DefinicionPaso[];
}

export const FLUJOS_PREDEFINIDOS: FlujoDefinicion[] = [
  {
    id: 'verificar_despliegue',
    nombre: 'Verificar un despliegue',
    descripcion: 'Comprueba que las URLs publicadas responden 200 y resume el estado del repositorio. Úsalo después de desplegar.',
    predefinido: true,
    pasos: [
      { tipo: 'salud_http', nombre: 'Salud de las URLs' },
      { tipo: 'estado_git', nombre: 'Estado del repositorio' },
    ],
  },
  {
    id: 'respaldo_rotacion',
    nombre: 'Respaldo con rotación',
    descripcion: 'Genera un respaldo en caliente de la base con su manifiesto sha256 y aplica la rotación configurada.',
    predefinido: true,
    pasos: [{ tipo: 'respaldo', nombre: 'Respaldo + manifiesto' }],
  },
  {
    id: 'auditoria_dependencias',
    nombre: 'Auditoría de dependencias y seguridad',
    descripcion: 'Resume dependencias (avisos locales, lockfile) y corre el escaneo de seguridad del proyecto.',
    predefinido: true,
    pasos: [
      { tipo: 'dependencias_resumen', nombre: 'Dependencias' },
      { tipo: 'seguridad_resumen', nombre: 'Seguridad' },
    ],
  },
  {
    id: 'preparar_release',
    nombre: 'Preparar un release',
    descripcion: 'Resume el estado del repo y PROPONE la siguiente versión con las notas de cambio (no publica ni etiqueta nada por sí solo).',
    predefinido: true,
    pasos: [
      { tipo: 'estado_git', nombre: 'Estado del repositorio' },
      { tipo: 'release_propuesta', nombre: 'Propuesta de versión y notas' },
    ],
  },
  {
    id: 'post_mortem',
    nombre: 'Post-mortem automático',
    descripcion: 'Reúne la bitácora de auditoría, los errores y el estado del git, y escribe un informe en Markdown en data/orquesta/informes/.',
    predefinido: true,
    pasos: [{ tipo: 'post_mortem', nombre: 'Recolectar y redactar' }],
  },
];

/* ─────────────────────────── Ejecutores de paso ─────────────────────────── */

function dirOrquesta(ctx: ServerCtx): string {
  return path.join(ctx.config.dataDir, 'orquesta');
}

export async function leerFlujosPropios(ctx: ServerCtx): Promise<FlujoDefinicion[]> {
  try {
    const crudo = JSON.parse(await fs.readFile(path.join(dirOrquesta(ctx), 'flujos.json'), 'utf8'));
    return (crudo.flujos ?? []) as FlujoDefinicion[];
  } catch {
    return [];
  }
}

export async function guardarFlujoPropio(ctx: ServerCtx, flujo: FlujoDefinicion): Promise<void> {
  const dir = dirOrquesta(ctx);
  await fs.mkdir(dir, { recursive: true });
  const propios = await leerFlujosPropios(ctx);
  const sinDuplicado = propios.filter((f) => f.id !== flujo.id);
  sinDuplicado.push(flujo);
  await fs.writeFile(path.join(dir, 'flujos.json'), JSON.stringify({ flujos: sinDuplicado }, null, 2), 'utf8');
}

export async function resolverFlujo(ctx: ServerCtx, id: string): Promise<FlujoDefinicion | undefined> {
  return [...FLUJOS_PREDEFINIDOS, ...(await leerFlujosPropios(ctx))].find((f) => f.id === id);
}

async function pasoEstadoGit(raiz: string): Promise<{ resumen: string; datos: unknown }> {
  const { stdout } = await exec('git', ['-C', raiz, 'status', '--porcelain'], { maxBuffer: 2 * 1024 * 1024 });
  // Un repositorio recién creado no tiene commits: eso no es un error.
  const log = await exec('git', ['-C', raiz, 'log', '-3', '--pretty=%h %s'], { maxBuffer: 1024 * 1024 })
    .then((r) => r.stdout)
    .catch(() => '');
  const sucios = stdout.split('\n').filter(Boolean).length;
  return {
    resumen: `${sucios} archivo(s) con cambios sin confirmar`,
    datos: { sin_confirmar: sucios, ultimos_commits: log.trim() ? log.trim().split('\n') : [] },
  };
}

async function pasoSaludHttp(urls: string[]): Promise<{ resumen: string; datos: unknown }> {
  if (!urls.length) throw new Error('El paso salud_http necesita "urls" (arreglo de direcciones http/https).');
  const resultados: { url: string; estado: number | null; ms: number; error?: string }[] = [];
  for (const url of urls.slice(0, 10)) {
    if (!/^https?:\/\//.test(url)) {
      resultados.push({ url, estado: null, ms: 0, error: 'solo se permiten http(s)' });
      continue;
    }
    const inicio = Date.now();
    try {
      const ctrl = new AbortController();
      const t = setTimeout(() => ctrl.abort(), 5000);
      const r = await fetch(url, { signal: ctrl.signal, redirect: 'follow' });
      clearTimeout(t);
      resultados.push({ url, estado: r.status, ms: Date.now() - inicio });
    } catch (e: any) {
      resultados.push({ url, estado: null, ms: Date.now() - inicio, error: e?.name === 'AbortError' ? 'sin respuesta en 5 s' : String(e?.cause?.code ?? e?.message ?? e) });
    }
  }
  const malos = resultados.filter((r) => r.estado === null || r.estado >= 400);
  return {
    resumen: malos.length === 0 ? `${resultados.length} URL(s) responden correctamente` : `${malos.length} de ${resultados.length} URL(s) con problemas`,
    datos: resultados,
  };
}

async function pasoRespaldo(ctx: ServerCtx, parametros: Record<string, unknown>): Promise<{ resumen: string; datos: unknown }> {
  const conservar = typeof parametros.conservar === 'number' ? parametros.conservar : 7;
  const r = await crearBackup(ctx, { conservar });
  return { resumen: `Respaldo creado: ${path.basename(r.ruta)} (${r.bytes_total} bytes, ${r.respaldos_conservados} conservado(s))`, datos: r };
}

async function pasoSeguridad(ctx: ServerCtx, raiz: string): Promise<{ resumen: string; datos: unknown }> {
  const e = await escanear(raiz);
  return {
    resumen: `Seguridad: puntaje ${e.puntaje}/100 (${e.semaforo}) · ${e.hallazgos.length} hallazgo(s)`,
    datos: { puntaje: e.puntaje, semaforo: e.semaforo, resumen: e.resumen },
  };
}

async function pasoDependencias(raiz: string): Promise<{ resumen: string; datos: unknown }> {
  let pkg: any = {};
  try {
    pkg = JSON.parse(await fs.readFile(path.join(raiz, 'package.json'), 'utf8'));
  } catch {
    throw new Error(`No encontré package.json en ${raiz}.`);
  }
  const cuenta = (o: unknown) => Object.keys((o as object) ?? {}).length;
  const lock = await fs.access(path.join(raiz, 'package-lock.json')).then(() => true).catch(() => false);
  return {
    resumen: `${cuenta(pkg.dependencies)} dependencias + ${cuenta(pkg.devDependencies)} de desarrollo · lockfile: ${lock ? 'sí' : 'no'}`,
    datos: { dependencias: cuenta(pkg.dependencies), dev: cuenta(pkg.devDependencies), lockfile: lock },
  };
}

async function pasoPendientes(raiz: string): Promise<{ resumen: string; datos: unknown }> {
  const grafo = await analizarProyecto(raiz);
  const marcadores = (grafo as unknown as { marcadores?: { tipo: string; archivo: string; linea: number }[] }).marcadores ?? [];
  const porTipo = marcadores.reduce<Record<string, number>>((acc, m) => {
    acc[m.tipo] = (acc[m.tipo] ?? 0) + 1;
    return acc;
  }, {});
  return {
    resumen: `${marcadores.length} pendiente(s) en el código`,
    datos: { total: marcadores.length, por_tipo: porTipo, primeros: marcadores.slice(0, 8) },
  };
}

async function pasoRelease(raiz: string): Promise<{ resumen: string; datos: unknown }> {
  const pkg = JSON.parse(await fs.readFile(path.join(raiz, 'package.json'), 'utf8'));
  const actual: string = pkg.version ?? '0.0.0';
  const [mayor = 0, menor = 0, parche = 0] = actual.split('.').map(Number);
  const propuesta = `${mayor}.${menor}.${(parche || 0) + 1}`;
  let notas: string[] = [];
  try {
    const { stdout: ultimoTag } = await exec('git', ['-C', raiz, 'describe', '--tags', '--abbrev=0'], { maxBuffer: 1024 * 1024 });
    const { stdout } = await exec('git', ['-C', raiz, 'log', `${ultimoTag.trim()}..HEAD`, '--pretty=%s'], { maxBuffer: 2 * 1024 * 1024 });
    notas = stdout.split('\n').filter(Boolean).slice(0, 25);
  } catch {
    const { stdout } = await exec('git', ['-C', raiz, 'log', '-10', '--pretty=%s'], { maxBuffer: 2 * 1024 * 1024 }).catch(() => ({ stdout: '' }));
    notas = stdout.split('\n').filter(Boolean);
  }
  return {
    resumen: `Versión propuesta: ${actual} → ${propuesta} · ${notas.length} cambio(s) para las notas`,
    datos: {
      version_actual: actual,
      version_propuesta: propuesta,
      notas_de_cambio: notas,
      para_publicar: `git tag v${propuesta} && git push --tags (lo haces tú, o se pide aprobación con aprobacion_solicitar)`,
    },
  };
}

async function pasoPostMortem(ctx: ServerCtx, raiz: string): Promise<{ resumen: string; datos: unknown }> {
  const eventos: any[] = [];
  try {
    const texto = await fs.readFile(ctx.audit.ruta, 'utf8');
    for (const linea of texto.trim().split('\n').slice(-500)) {
      try {
        eventos.push(JSON.parse(linea));
      } catch {
        /* línea corrupta: se ignora */
      }
    }
  } catch {
    /* sin bitácora */
  }
  const recientes = eventos.slice(-200);
  const errores = recientes.filter((e) => e.ok === false || e.error);
  const destructivas = recientes.filter((e) => e.tipo === 'tool_destructiva');
  let diff = '';
  try {
    const { stdout } = await exec('git', ['-C', raiz, 'diff', '--stat'], { maxBuffer: 2 * 1024 * 1024 });
    diff = stdout.trim();
  } catch {
    diff = '(sin git)';
  }
  const informe = [
    `# Post-mortem — ${path.basename(raiz)}`,
    '',
    `Generado: ${new Date().toISOString()}`,
    '',
    '## Actividad reciente (auditoría)',
    `- Eventos considerados: ${recientes.length}`,
    `- Operaciones destructivas auditadas: ${destructivas.length}`,
    `- Errores registrados: ${errores.length}`,
    '',
    '## Errores (últimos)',
    ...(errores.slice(-10).map((e) => `- \`${e.t} ${e.herramienta ?? ''}\` — ${String(e.error ?? e.detalle ?? '').slice(0, 160)}`)),
    '',
    '## Cambios sin confirmar',
    '```',
    diff || '(sin cambios)',
    '```',
    '',
    '## Preguntas para la reunión',
    '1. ¿Qué se estaba intentando cuando ocurrió la falla?',
    '2. ¿Qué se aprendió que deba quedar en una prueba automática?',
    '3. ¿Qué paso del flujo necesita un chequeo previo para no repetirlo?',
    '',
  ].join('\n');
  const dir = path.join(dirOrquesta(ctx), 'informes');
  await fs.mkdir(dir, { recursive: true });
  const archivo = path.join(dir, `post-mortem-${path.basename(raiz)}-${sello()}.md`);
  await fs.writeFile(archivo, informe, 'utf8');
  return { resumen: `Informe escrito: ${archivo} (${errores.length} error(es) resumidos)`, datos: { informe: archivo, errores: errores.length } };
}

export function sello(): string {
  return new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
}

async function ejecutarPaso(
  ctx: ServerCtx,
  paso: DefinicionPaso,
  raiz: string,
): Promise<ResultadoPaso> {
  const nombre = paso.nombre ?? paso.tipo;
  const inicio = Date.now();
  try {
    const parametros = paso.parametros ?? {};
    let r: { resumen: string; datos: unknown };
    switch (paso.tipo) {
      case 'estado_git':
        r = await pasoEstadoGit(raiz);
        break;
      case 'salud_http':
        r = await pasoSaludHttp((parametros.urls as string[]) ?? []);
        break;
      case 'respaldo':
        r = await pasoRespaldo(ctx, parametros);
        break;
      case 'seguridad_resumen':
        r = await pasoSeguridad(ctx, raiz);
        break;
      case 'dependencias_resumen':
        r = await pasoDependencias(raiz);
        break;
      case 'pendientes_resumen':
        r = await pasoPendientes(raiz);
        break;
      case 'release_propuesta':
        r = await pasoRelease(raiz);
        break;
      case 'post_mortem':
        r = await pasoPostMortem(ctx, raiz);
        break;
      case 'espera':
        r = { resumen: `Espera simulada de ${Number(parametros.segundos ?? 0)} s`, datos: null };
        await new Promise((res) => setTimeout(res, Math.min(5000, Number(parametros.segundos ?? 0) * 1000)));
        break;
      default:
        throw new Error(`Tipo de paso no soportado: ${paso.tipo}`);
    }
    return { paso: nombre, tipo: paso.tipo, ok: true, ms: Date.now() - inicio, ...r };
  } catch (e: any) {
    return { paso: nombre, tipo: paso.tipo, ok: false, ms: Date.now() - inicio, resumen: 'Falló el paso', error: e?.message ?? String(e) };
  }
}

/* ─────────────────────────── Corrida de un flujo ─────────────────────────── */

export async function ejecutarFlujo(
  ctx: ServerCtx,
  opciones: { flujo: string; raiz?: string; parametros?: Record<string, unknown>; dry_run?: boolean; detener_en_error?: boolean },
): Promise<ResultadoFlujo> {
  const def = await resolverFlujo(ctx, opciones.flujo);
  if (!def) throw new Error(`No existe el flujo "${opciones.flujo}". Usa flujo_listar para ver los disponibles.`);
  const raiz = opciones.raiz ?? ctx.config.allowedGitRoots[0];
  if (!raiz) throw new Error('No hay rutas autorizadas (ALLOWED_GIT_ROOTS).');

  // Los parámetros se combinan: los del flujo definido + los de la corrida.
  const pasos = def.pasos.map((p) => ({ ...p, parametros: { ...(p.parametros ?? {}), ...(opciones.parametros ?? {}) } }));
  const inicio = new Date().toISOString();
  const t0 = Date.now();

  if (opciones.dry_run) {
    return {
      flujo: def.id,
      nombre: def.nombre,
      dry_run: true,
      ok: true,
      inicio,
      duracion_ms: 0,
      pasos: pasos.map((p) => ({ paso: p.nombre ?? p.tipo, tipo: p.tipo, ok: true, ms: 0, resumen: 'No ejecutado (dry-run)' })),
    };
  }

  const resultados: ResultadoPaso[] = [];
  for (const paso of pasos) {
    const r = await ejecutarPaso(ctx, paso, raiz);
    resultados.push(r);
    if (!r.ok && opciones.detener_en_error !== false) break;
  }
  const ok = resultados.every((r) => r.ok);
  const duracion = Date.now() - t0;

  const dir = path.join(dirOrquesta(ctx), 'runs');
  await fs.mkdir(dir, { recursive: true });
  const acta = path.join(dir, `${def.id}-${sello()}.json`);
  await fs.writeFile(
    acta,
    JSON.stringify({ flujo: def.id, nombre: def.nombre, raiz, inicio, duracion_ms: duracion, ok, pasos: resultados }, null, 2),
    'utf8',
  );
  await ctx.audit.registrar({
    tipo: 'flujo_ejecutado',
    cliente: ctx.apiKey?.nombre ?? 'local',
    herramienta: 'flujo_ejecutar',
    ok,
    detalle: `${def.id} · ${resultados.filter((r) => r.ok).length}/${resultados.length} paso(s) · ${duracion} ms`,
  });
  return { flujo: def.id, nombre: def.nombre, dry_run: false, ok, inicio, duracion_ms: duracion, pasos: resultados, acta };
}

/** Últimas corridas registradas (para flujo_estado). */
export async function ultimasCorridas(ctx: ServerCtx, limite = 10): Promise<{ flujo: string; ok: boolean; inicio: string; duracion_ms: number; pasos_fallidos: string[] }[]> {
  const dir = path.join(dirOrquesta(ctx), 'runs');
  let archivos: string[];
  try {
    archivos = (await fs.readdir(dir)).filter((a) => a.endsWith('.json')).sort();
  } catch {
    return [];
  }
  // El nombre del archivo empieza por el id del flujo: hay que ordenar por la
  // FECHA del acta (inicio), no por el nombre, para que "lo más reciente" sea cierto.
  const recientes = archivos.slice(-200);
  const corridas: { flujo: string; ok: boolean; inicio: string; duracion_ms: number; pasos_fallidos: string[] }[] = [];
  for (const a of recientes) {
    try {
      const d = JSON.parse(await fs.readFile(path.join(dir, a), 'utf8'));
      corridas.push({
        flujo: d.flujo,
        ok: Boolean(d.ok),
        inicio: d.inicio,
        duracion_ms: d.duracion_ms ?? 0,
        pasos_fallidos: (d.pasos ?? []).filter((p: ResultadoPaso) => !p.ok).map((p: ResultadoPaso) => p.paso),
      });
    } catch {
      /* acta ilegible: se omite */
    }
  }
  return corridas.sort((a, b) => b.inicio.localeCompare(a.inicio)).slice(0, limite);
}

export function idFlujo(nombre: string): string {
  return nombre
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 40) || `flujo_${crypto.randomBytes(2).toString('hex')}`;
}

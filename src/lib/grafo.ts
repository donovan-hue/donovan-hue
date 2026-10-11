/**
 * GEMELO (fase 4A) — analizador estático del proyecto.
 *
 * Construye un mapa vivo del código sin compilar y sin servicios externos:
 * archivos → imports/dependencias → rutas HTTP → servicios → pruebas.
 * Funciona con TypeScript/JavaScript moderno y tolera proyectos a medias
 * (objetivo: poder analizar código abandonado o dañado, no solo el perfecto).
 */
import fs from 'node:fs/promises';
import path from 'node:path';

/** Directorios que nunca se recorren (dependencias, artefactos, datos). */
export const DIRS_IGNORADOS = new Set([
  'node_modules', '.git', 'dist', 'build', 'coverage', '.venv', '__pycache__',
  'out', 'target', '.next', '.cache', '.turbo', 'vendor', 'data',
]);

const EXT_CODIGO = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs', '.mts', '.cts']);
const ES_PRUEBA = /\.(test|spec)\.[cm]?[jt]sx?$/;
const ES_CONFIG = /^(vitest|jest|vite|rollup|webpack|tsup|eslint|prettier|tailwind|postcss|esbuild)\.config\.[cm]?[jt]s$/;

/** Paquetes con significado operativo claro, para el mapa de servicios. */
const SERVICIOS_CONOCIDOS: Record<string, string> = {
  'better-sqlite3': 'SQLite local',
  sqlite3: 'SQLite',
  pg: 'PostgreSQL',
  mysql2: 'MySQL',
  mongoose: 'MongoDB',
  mongodb: 'MongoDB',
  redis: 'Redis',
  ioredis: 'Redis',
  express: 'servidor HTTP (Express)',
  fastify: 'servidor HTTP (Fastify)',
  koa: 'servidor HTTP (Koa)',
  '@modelcontextprotocol/sdk': 'SDK de MCP',
  zod: 'validación de datos',
  axios: 'HTTP saliente',
  undici: 'HTTP saliente',
  'node-fetch': 'HTTP saliente',
  dotenv: 'variables de entorno',
  jsonwebtoken: 'tokens JWT',
  bcrypt: 'hash de contraseñas',
  '@anthropic-ai/sdk': 'modelos de IA',
  openai: 'modelos de IA',
};

export interface Importacion {
  especificador: string;
  resuelto?: string;
  linea: number;
}

export interface Exportacion {
  nombre: string;
  clase: 'funcion' | 'clase' | 'constante' | 'tipo' | 'default';
  linea: number;
}

export interface RutaDetectada {
  metodo: string;
  ruta: string;
  linea: number;
}

export interface Marcador {
  tipo: 'TODO' | 'FIXME' | 'HACK' | 'XXX' | 'STUB';
  linea: number;
  texto: string;
}

export interface FuncionDetectada {
  nombre: string;
  linea: number;
  /** Cuerpo normalizado (sin comentarios ni espacios) para detectar duplicados. */
  cuerpo: string;
}

export interface ArchivoInfo {
  ruta: string;
  nombre: string;
  extension: string;
  bytes: number;
  lineas: number;
  /** Agrupación razonable para humanos: 'src/tools', 'tests', 'raíz'… */
  modulo: string;
  esPrueba: boolean;
  esEntrada: boolean;
  imports: Importacion[];
  exports: Exportacion[];
  rutas: RutaDetectada[];
  paquetes: string[];
  marcadores: Marcador[];
  funciones: FuncionDetectada[];
}

export interface PaqueteJson {
  name?: string;
  version?: string;
  main?: string;
  bin?: string | Record<string, string>;
  scripts: Record<string, string>;
  dependencies: string[];
}

export interface Grafo {
  raiz: string;
  nombre: string;
  generadoEn: string;
  archivos: ArchivoInfo[];
  porRuta: Map<string, ArchivoInfo>;
  importa: Map<string, Set<string>>;
  importadoPor: Map<string, Set<string>>;
  rutas: (RutaDetectada & { archivo: string })[];
  servicios: { paquete: string; descripcion: string; usos: number; archivos: string[] }[];
  entradas: string[];
  paquete?: PaqueteJson;
  totalLineas: number;
  otrosArchivos: Record<string, number>;
}

const norm = (p: string) => p.split(path.sep).join('/');

async function listarArchivos(raiz: string): Promise<string[]> {
  const resultado: string[] = [];
  async function recorrer(dir: string): Promise<void> {
    let entradas;
    try {
      entradas = await fs.readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entradas) {
      if (e.isDirectory()) {
        if (DIRS_IGNORADOS.has(e.name) || e.name.startsWith('.')) continue;
        await recorrer(path.join(dir, e.name));
      } else if (e.isFile()) {
        resultado.push(path.join(dir, e.name));
      }
    }
  }
  await recorrer(raiz);
  return resultado;
}

const RE_IMPORT_GLOBAL =
  /(?:^|[^\w$.])(?:import|export)\s[^'"]*?\sfrom\s*['"]([^'"]+)['"]|(?:^|[^\w$.])import\s*['"]([^'"]+)['"]|(?:^|[^\w$.])require\s*\(\s*['"]([^'"]+)['"]\s*\)|(?:^|[^\w$.])import\s*\(\s*['"]([^'"]+)['"]\s*\)/g;

const RE_EXPORT_FN = /^\s*export\s+(?:async\s+)?function\s+([A-Za-z0-9_$]+)/;
const RE_EXPORT_CLASE = /^\s*export\s+class\s+([A-Za-z0-9_$]+)/;
const RE_EXPORT_CONST = /^\s*export\s+(?:const|let|var)\s+([A-Za-z0-9_$]+)/;
const RE_EXPORT_TIPO = /^\s*export\s+(?:interface|type)\s+([A-Za-z0-9_$]+)/;
const RE_EXPORT_DEFAULT = /^\s*export\s+default\b/;

const RE_RUTA_EXPRESS = /\b(?:app|router|servidor|srv|rutas?|routes?)\s*\.\s*(get|post|put|patch|delete|all|use)\s*\(\s*['"`]([^'"`]+)['"`]/i;
const RE_RUTA_IGUAL = /\b(?:pathname|url|ruta|path)\s*(?:===|==)\s*['"`](\/[^'"`]*)['"`]/g;
const RE_RUTA_STARTS = /\b(?:pathname|url|ruta|path)\s*\.\s*startsWith\s*\(\s*['"`](\/[^'"`]*)['"`]/g;
const RE_RUTA_CASE = /^\s*case\s+['"`](\/[^'"`]*)['"`]/g;

const RE_MARCADOR = /\b(TODO|FIXME|HACK|XXX)\b[:：]?\s*(.{0,120})/;

const RE_FN = /^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+([A-Za-z0-9_$]+)\s*\(/;
const RE_FLECHA = /^\s*(?:export\s+)?const\s+([A-Za-z0-9_$]+)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z0-9_$]+)\s*=>\s*\{/;

function extraerFunciones(lineas: string[]): FuncionDetectada[] {
  const salida: FuncionDetectada[] = [];
  for (let i = 0; i < lineas.length; i++) {
    const linea = lineas[i]!;
    const m = RE_FN.exec(linea) ?? RE_FLECHA.exec(linea);
    if (!m) continue;
    let profundidad = 0;
    let abierto = false;
    const buffer: string[] = [];
    let j = i;
    while (j < lineas.length && j - i <= 400) {
      const actual = lineas[j]!;
      for (const ch of actual) {
        if (ch === '{') {
          profundidad++;
          abierto = true;
        } else if (ch === '}') {
          profundidad--;
        }
      }
      buffer.push(actual);
      if (abierto && profundidad <= 0) break;
      j++;
    }
    if (!abierto || profundidad > 0) continue;
    const cuerpo = buffer
      .join('\n')
      .replace(/\/\/.*$/gm, '')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\s+/g, ' ')
      .trim();
    if (cuerpo.length >= 120) salida.push({ nombre: m[1]!, linea: i + 1, cuerpo });
    i = j;
  }
  return salida;
}

function paqueteRaiz(especificador: string): string | undefined {
  if (especificador.startsWith('.') || especificador.startsWith('/') || especificador.startsWith('#')) return undefined;
  if (especificador.startsWith('node:')) return undefined;
  const partes = especificador.split('/');
  return especificador.startsWith('@') ? partes.slice(0, 2).join('/') : partes[0];
}

function moduloDe(rel: string): string {
  const partes = rel.split('/');
  if (partes.length === 1) return 'raíz';
  if (partes[0] === 'src' && partes.length > 2) return `src/${partes[1]}`;
  return partes[0]!;
}

export async function analizarProyecto(raiz: string): Promise<Grafo> {
  const todos = await listarArchivos(raiz);
  const archivos: ArchivoInfo[] = [];
  const otrosArchivos: Record<string, number> = {};

  for (const absoluto of todos) {
    const rel = norm(path.relative(raiz, absoluto));
    const ext = path.extname(absoluto).toLowerCase();
    if (!EXT_CODIGO.has(ext)) {
      otrosArchivos[ext || '(sin extensión)'] = (otrosArchivos[ext || '(sin extensión)'] ?? 0) + 1;
      continue;
    }
    let contenido = '';
    try {
      contenido = await fs.readFile(absoluto, 'utf8');
    } catch {
      continue;
    }
    const lineas = contenido.split('\n');
    const imports: Importacion[] = [];
    const exports: Exportacion[] = [];
    const rutas: RutaDetectada[] = [];
    const marcadores: Marcador[] = [];
    const paquetes = new Set<string>();

    // Imports (soporta importaciones multilínea: se busca sobre el texto completo).
    for (const m of contenido.matchAll(RE_IMPORT_GLOBAL)) {
      const especificador = m[1] ?? m[2] ?? m[3] ?? m[4];
      if (!especificador) continue;
      const linea = (contenido.slice(0, m.index).match(/\n/g) ?? []).length + 1;
      imports.push({ especificador, linea });
      const pkg = paqueteRaiz(especificador);
      if (pkg) paquetes.add(pkg);
    }

    lineas.forEach((linea, idx) => {
      const numero = idx + 1;
      if (RE_EXPORT_FN.test(linea)) exports.push({ nombre: RE_EXPORT_FN.exec(linea)![1]!, clase: 'funcion', linea: numero });
      else if (RE_EXPORT_CLASE.test(linea)) exports.push({ nombre: RE_EXPORT_CLASE.exec(linea)![1]!, clase: 'clase', linea: numero });
      else if (RE_EXPORT_CONST.test(linea)) exports.push({ nombre: RE_EXPORT_CONST.exec(linea)![1]!, clase: 'constante', linea: numero });
      else if (RE_EXPORT_TIPO.test(linea)) exports.push({ nombre: RE_EXPORT_TIPO.exec(linea)![1]!, clase: 'tipo', linea: numero });
      else if (RE_EXPORT_DEFAULT.test(linea)) exports.push({ nombre: 'default', clase: 'default', linea: numero });

      for (const m of linea.matchAll(new RegExp(RE_RUTA_EXPRESS.source, 'gi'))) {
        rutas.push({ metodo: m[1]!.toUpperCase(), ruta: m[2]!, linea: numero });
      }
      for (const m of linea.matchAll(RE_RUTA_IGUAL)) rutas.push({ metodo: 'ANY', ruta: m[1]!, linea: numero });
      for (const m of linea.matchAll(RE_RUTA_STARTS)) rutas.push({ metodo: 'ANY', ruta: `${m[1]!}*`, linea: numero });
      for (const m of linea.matchAll(RE_RUTA_CASE)) rutas.push({ metodo: 'ANY', ruta: m[1]!, linea: numero });

      const marca = RE_MARCADOR.exec(linea);
      if (marca) marcadores.push({ tipo: marca[1] as Marcador['tipo'], linea: numero, texto: marca[2]!.trim().slice(0, 120) });
      if (/throw\s+new\s+Error\(\s*['"][^'"]*(no implementad|not implemented|pendiente|por hacer|sin implementar)/i.test(linea)) {
        marcadores.push({ tipo: 'STUB', linea: numero, texto: 'código sin implementar (lanza error de pendiente)' });
      }
    });

    const esPrueba = ES_PRUEBA.test(rel);
    archivos.push({
      ruta: rel,
      nombre: path.basename(rel),
      extension: ext,
      bytes: Buffer.byteLength(contenido, 'utf8'),
      lineas: lineas.length,
      modulo: moduloDe(rel),
      esPrueba,
      esEntrada: false,
      imports,
      exports,
      rutas,
      paquetes: [...paquetes],
      marcadores,
      funciones: esPrueba ? [] : extraerFunciones(lineas),
    });
  }

  const porRuta = new Map(archivos.map((a) => [a.ruta, a]));

  // ── Resolver imports relativos a archivos reales (.js → .ts incluido) ──
  const resolver = (desde: string, especificador: string): string | undefined => {
    if (!especificador.startsWith('.')) return undefined;
    const base = norm(path.normalize(path.join(path.dirname(desde), especificador)));
    const candidatos = [base];
    const sinExt = base.replace(/\.(js|mjs|cjs)$/, '');
    for (const ext of ['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs', '.mts', '.cts']) candidatos.push(`${sinExt}${ext}`);
    for (const ext of ['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs']) candidatos.push(`${base}/index${ext}`);
    for (const c of candidatos) if (porRuta.has(c)) return c;
    return undefined;
  };

  const importa = new Map<string, Set<string>>();
  const importadoPor = new Map<string, Set<string>>();
  for (const a of archivos) {
    const destinos = new Set<string>();
    for (const imp of a.imports) {
      const resuelto = resolver(a.ruta, imp.especificador);
      if (resuelto) {
        imp.resuelto = resuelto;
        destinos.add(resuelto);
      }
    }
    importa.set(a.ruta, destinos);
    for (const d of destinos) {
      if (!importadoPor.has(d)) importadoPor.set(d, new Set());
      importadoPor.get(d)!.add(a.ruta);
    }
  }

  // ── package.json ──
  let paquete: PaqueteJson | undefined;
  try {
    const crudo = JSON.parse(await fs.readFile(path.join(raiz, 'package.json'), 'utf8'));
    paquete = {
      name: crudo.name,
      version: crudo.version,
      main: crudo.main,
      bin: crudo.bin,
      scripts: crudo.scripts ?? {},
      dependencies: Object.keys(crudo.dependencies ?? {}),
    };
  } catch {
    paquete = undefined;
  }

  // ── Entradas: main/bin del paquete (mapeando dist→src) + candidatos clásicos ──
  const candidatosEntrada = new Set<string>();
  const agregarCandidato = (c?: string) => {
    if (!c) return;
    const limpio = norm(c).replace(/^\.\//, '');
    candidatosEntrada.add(limpio);
    if (limpio.startsWith('dist/')) candidatosEntrada.add(limpio.replace(/^dist\//, 'src/').replace(/\.(js|mjs|cjs)$/, '.ts'));
    if (limpio.startsWith('build/')) candidatosEntrada.add(limpio.replace(/^build\//, 'src/').replace(/\.(js|mjs|cjs)$/, '.ts'));
  };
  agregarCandidato(paquete?.main);
  if (typeof paquete?.bin === 'string') agregarCandidato(paquete.bin);
  else if (paquete?.bin) Object.values(paquete.bin).forEach(agregarCandidato);
  for (const c of ['src/index.ts', 'src/index.js', 'index.js', 'index.ts', 'src/main.ts', 'main.js', 'server.js', 'app.js', 'src/cli.ts', 'bin/cli.js']) {
    candidatosEntrada.add(c);
  }
  const entradas: string[] = [];
  for (const c of candidatosEntrada) {
    const archivo = porRuta.get(c);
    if (archivo) {
      archivo.esEntrada = true;
      entradas.push(c);
    }
  }
  // Los scripts sueltos de 'scripts/' se ejecutan directamente: trátalos como entradas.
  for (const a of archivos) {
    if (a.ruta.startsWith('scripts/') && !a.esPrueba) {
      a.esEntrada = true;
      entradas.push(a.ruta);
    }
  }

  // ── Servicios (paquetes externos) ──
  const porPaquete = new Map<string, Set<string>>();
  for (const a of archivos) {
    for (const p of a.paquetes) {
      if (!porPaquete.has(p)) porPaquete.set(p, new Set());
      porPaquete.get(p)!.add(a.ruta);
    }
  }
  const servicios = [...porPaquete.entries()]
    .map(([p, usos]) => ({
      paquete: p,
      descripcion: SERVICIOS_CONOCIDOS[p] ?? 'paquete externo',
      usos: usos.size,
      archivos: [...usos].sort().slice(0, 5),
    }))
    .sort((a, b) => b.usos - a.usos || a.paquete.localeCompare(b.paquete));

  const rutas = archivos.flatMap((a) => a.rutas.map((r) => ({ ...r, archivo: a.ruta })));
  const totalLineas = archivos.reduce((acc, a) => acc + a.lineas, 0);

  return {
    raiz,
    nombre: paquete?.name ?? path.basename(raiz),
    generadoEn: new Date().toISOString(),
    archivos: archivos.sort((a, b) => a.ruta.localeCompare(b.ruta)),
    porRuta,
    importa,
    importadoPor,
    rutas,
    servicios,
    entradas: [...new Set(entradas)],
    paquete,
    totalLineas,
    otrosArchivos,
  };
}

export interface DetalleImpacto {
  archivo: string;
  evidencia: { linea: number }[];
}

export interface Impacto {
  objetivo: string;
  archivosObjetivo: string[];
  dependientesDirectos: DetalleImpacto[];
  dependientesTransitivos: string[];
  rutasAfectadas: (RutaDetectada & { archivo: string })[];
  pruebasAfectadas: string[];
  serviciosAfectados: string[];
  nivelRiesgo: 'bajo' | 'medio' | 'alto';
  puntaje: number;
  explicacion: string;
}

/** Localiza archivos por ruta (sufijo) o por símbolo exportado. */
export function localizarObjetivo(grafo: Grafo, objetivo: string): string[] {
  const limpio = norm(objetivo.replace(/^\.\//, ''));
  const porSufijo = grafo.archivos.filter((a) => a.ruta === limpio || a.ruta.endsWith(`/${limpio}`));
  if (porSufijo.length > 0) return porSufijo.map((a) => a.ruta);
  const porSimbolo = grafo.archivos.filter((a) => a.exports.some((e) => e.nombre === objetivo));
  if (porSimbolo.length > 0) return porSimbolo.map((a) => a.ruta);
  return [];
}

export function calcularImpacto(grafo: Grafo, objetivo: string, profundidad = 3): Impacto {
  const objetivos = localizarObjetivo(grafo, objetivo);
  if (objetivos.length === 0) {
    throw new Error(
      `No encontré "${objetivo}" en el mapa de ${grafo.raiz}. Prueba con una ruta (ej. src/lib/cfdi.ts) o un símbolo exportado (ej. generarBorradorCfdi).`,
    );
  }

  const visitados = new Set<string>();
  const porNivel = new Map<string, number>();
  for (const o of objetivos) porNivel.set(o, 0);
  let frontera = [...objetivos];
  for (let nivel = 1; nivel <= profundidad && frontera.length > 0; nivel++) {
    const siguiente: string[] = [];
    for (const f of frontera) {
      for (const dep of grafo.importadoPor.get(f) ?? []) {
        if (porNivel.has(dep) && porNivel.get(dep)! <= nivel) continue;
        if (!visitados.has(dep)) {
          visitados.add(dep);
          porNivel.set(dep, nivel);
          siguiente.push(dep);
        }
      }
    }
    frontera = siguiente;
  }

  const afectados = [...porNivel.keys()].filter((f) => !objetivos.includes(f));
  const dependientesDirectos: DetalleImpacto[] = afectados
    .filter((f) => porNivel.get(f) === 1)
    .map((f) => {
      const archivo = grafo.porRuta.get(f)!;
      const evidencia = archivo.imports.filter((i) => objetivos.includes(i.resuelto ?? '')).map((i) => ({ linea: i.linea }));
      return { archivo: f, evidencia };
    });

  const todosAfectados = [...objetivos, ...afectados];
  const rutasAfectadas = grafo.rutas.filter((r) => todosAfectados.includes(r.archivo));
  const pruebasAfectadas = todosAfectados.filter((f) => grafo.porRuta.get(f)?.esPrueba);
  const serviciosAfectados = [
    ...new Set(objetivos.flatMap((o) => (grafo.porRuta.get(o)?.paquetes ?? []).map((p) => SERVICIOS_CONOCIDOS[p] ?? p))),
  ];

  const puntaje = rutasAfectadas.length * 2 + dependientesDirectos.length + pruebasAfectadas.length * 2;
  const nivelRiesgo: Impacto['nivelRiesgo'] = puntaje >= 8 ? 'alto' : puntaje >= 3 ? 'medio' : 'bajo';
  const explicacion =
    nivelRiesgo === 'alto'
      ? `Cambio de alto impacto: toca ${rutasAfectadas.length} ruta(s), ${dependientesDirectos.length} archivo(s) directo(s) y ${pruebasAfectadas.length} prueba(s). Ejecuta la suite completa antes de aceptar el cambio.`
      : nivelRiesgo === 'medio'
        ? `Impacto moderado: ${dependientesDirectos.length} archivo(s) dependen directamente y ${rutasAfectadas.length} ruta(s) podrían verse afectadas. Corre las pruebas señaladas.`
        : `Impacto bajo: pocos dependientes (${dependientesDirectos.length}) y ${rutasAfectadas.length} ruta(s) afectadas. Aun así, verifica compilación y pruebas del módulo.`;

  return {
    objetivo,
    archivosObjetivo: objetivos,
    dependientesDirectos,
    dependientesTransitivos: afectados.filter((f) => (porNivel.get(f) ?? 0) > 1),
    rutasAfectadas,
    pruebasAfectadas,
    serviciosAfectados,
    nivelRiesgo,
    puntaje,
    explicacion,
  };
}

export interface ArchivoDesconectado {
  archivo: string;
  motivo: string;
  rutas: number;
}

/** Archivos que nadie importa y no son entradas, pruebas ni configuración. */
export function archivosDesconectados(grafo: Grafo): ArchivoDesconectado[] {
  return grafo.archivos
    .filter((a) => {
      if (a.esEntrada || a.esPrueba) return false;
      if (a.ruta.endsWith('.d.ts')) return false;
      if (ES_CONFIG.test(a.nombre)) return false;
      if (a.ruta.startsWith('scripts/')) return false;
      const dependientes = grafo.importadoPor.get(a.ruta);
      return !dependientes || dependientes.size === 0;
    })
    .map((a) => ({
      archivo: a.ruta,
      motivo: a.rutas.length > 0 ? `define ${a.rutas.length} ruta(s) pero nadie lo importa: posible módulo huérfano` : 'ningún archivo lo importa (código desconectado)',
      rutas: a.rutas.length,
    }))
    .sort((a, b) => b.rutas - a.rutas || a.archivo.localeCompare(b.archivo));
}

export interface DuplicadoFuncion {
  firma: string;
  apariciones: { archivo: string; nombre: string; linea: number }[];
}

/** Cuerpos de función idénticos (normalizados) en dos o más lugares. */
export function funcionesDuplicadas(grafo: Grafo): DuplicadoFuncion[] {
  const porCuerpo = new Map<string, { archivo: string; nombre: string; linea: number }[]>();
  for (const a of grafo.archivos) {
    for (const f of a.funciones) {
      if (!porCuerpo.has(f.cuerpo)) porCuerpo.set(f.cuerpo, []);
      porCuerpo.get(f.cuerpo)!.push({ archivo: a.ruta, nombre: f.nombre, linea: f.linea });
    }
  }
  return [...porCuerpo.entries()]
    .filter(([, apariciones]) => apariciones.length >= 2)
    .map(([cuerpo, apariciones]) => ({
      firma: cuerpo.slice(0, 100),
      apariciones: apariciones.sort((a, b) => a.archivo.localeCompare(b.archivo)),
    }));
}

/** Nombres exportados que se repiten en varios archivos del proyecto. */
export function exportsRepetidos(grafo: Grafo): { nombre: string; archivos: string[] }[] {
  const porNombre = new Map<string, Set<string>>();
  for (const a of grafo.archivos) {
    if (a.esPrueba) continue;
    for (const e of a.exports) {
      if (e.nombre === 'default' || e.clase === 'tipo') continue;
      if (!porNombre.has(e.nombre)) porNombre.set(e.nombre, new Set());
      porNombre.get(e.nombre)!.add(a.ruta);
    }
  }
  return [...porNombre.entries()]
    .filter(([, archivos]) => archivos.size >= 2)
    .map(([nombre, archivos]) => ({ nombre, archivos: [...archivos].sort() }))
    .sort((a, b) => b.archivos.length - a.archivos.length || a.nombre.localeCompare(b.nombre));
}

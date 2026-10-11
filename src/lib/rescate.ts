/**
 * RESCATE (fase 4A) — diagnóstico de proyectos abandonados o dañados.
 *
 * Reconstruye la arquitectura desde el código existente, detecta lo que falta
 * (marcadores, stubs, archivos vacíos), lo desconectado, lo duplicado y los
 * desajustes entre la documentación y la realidad; y produce un plan de
 * terminación ORDENADO por impacto (qué desbloquea más primero).
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import {
  archivosDesconectados,
  exportsRepetidos,
  funcionesDuplicadas,
  type Grafo,
} from './grafo.js';

export interface Hueco {
  tipo: 'pendiente' | 'stub' | 'archivo_vacio' | 'duplicado' | 'desconectado' | 'documentacion';
  archivo: string;
  linea?: number;
  detalle: string;
  gravedad: 'alta' | 'media' | 'baja';
}

export interface ItemPlan {
  orden: number;
  titulo: string;
  detalle: string;
  archivo?: string;
  impacto: number;
  esfuerzo: 'S' | 'M' | 'L';
}

export interface ModuloReconstruido {
  modulo: string;
  proposito: string;
  archivos: number;
  lineas: number;
  exports: number;
  rutas: number;
  pruebas: number;
}

export interface ReclamoDoc {
  tipo: 'script' | 'ruta_archivo' | 'cifra_pruebas';
  dicho: string;
  realidad: string;
  coincide: boolean;
}

export interface Diagnostico {
  raiz: string;
  proyecto: string;
  esRepoGit: boolean;
  veredicto: string;
  estado: 'sano' | 'en obra' | 'abandonado' | 'dañado';
  arquitectura: {
    modulos: ModuloReconstruido[];
    entradas: string[];
    rutas: number;
    pruebas: { archivos: number; casos: number };
    servicios: { paquete: string; descripcion: string; usos: number }[];
    totalArchivos: number;
    totalLineas: number;
  };
  huecos: Hueco[];
  documentacion: {
    archivo?: string;
    reclamos: ReclamoDoc[];
    desajustes: number;
  };
  plan: ItemPlan[];
  resumen: {
    pendientes: number;
    stubs: number;
    vacios: number;
    desconectados: number;
    duplicados: number;
    desajustes_docs: number;
    total_huecos: number;
  };
}

const PROPOSITOS: [RegExp, string][] = [
  [/tools?|herramientas/, 'herramientas expuestas al cliente'],
  [/rutas?|routes?|endpoints?|api/, 'endpoints y controladores HTTP'],
  [/servicios?|services?/, 'integraciones, datos y lógica de servicio'],
  [/lib|utils?|helpers?|shared|comun/, 'utilidades compartidas'],
  [/tests?|pruebas|__tests__|spec/, 'pruebas automatizadas'],
  [/db|base|persistencia|models?|repos/, 'persistencia y modelos de datos'],
  [/auth|seguridad|security/, 'autenticación y permisos'],
  [/config/, 'configuración y variables de entorno'],
  [/ui|vistas|paginas|pages|components?/, 'interfaz de usuario'],
  [/scripts?/, 'tareas de operación'],
];

function propositoDe(modulo: string): string {
  for (const [re, texto] of PROPOSITOS) if (re.test(modulo.toLowerCase())) return texto;
  return 'módulo del proyecto';
}

async function compararDocs(grafo: Grafo, casosPrueba: number): Promise<Diagnostico['documentacion']> {
  const candidatos = ['README.md', 'readme.md', 'Readme.md', 'README.MD'];
  let archivoDocs: string | undefined;
  let contenido = '';
  for (const c of candidatos) {
    try {
      contenido = await fs.readFile(path.join(grafo.raiz, c), 'utf8');
      archivoDocs = c;
      break;
    } catch {
      /* siguiente */
    }
  }
  if (!archivoDocs) return { reclamos: [], desajustes: 0 };

  const reclamos: ReclamoDoc[] = [];

  // 1) Scripts mencionados: `npm run X` debe existir en package.json.
  if (grafo.paquete) {
    const scripts = new Set(Object.keys(grafo.paquete.scripts));
    const dichos = new Set<string>();
    for (const m of contenido.matchAll(/npm run ([a-z0-9:_-]+)/gi)) dichos.add(m[1]!);
    for (const s of dichos) {
      reclamos.push({
        tipo: 'script',
        dicho: `npm run ${s}`,
        realidad: scripts.has(s) ? 'existe en package.json' : 'NO existe en package.json',
        coincide: scripts.has(s),
      });
    }
  }

  // 2) Rutas de archivo en backticks que no existen.
  const vistas = new Set<string>();
  for (const m of contenido.matchAll(/`([\w./-]+\.(?:ts|tsx|js|jsx|mjs|cjs|md|json|ya?ml|html|sh))`/g)) {
    const p = m[1]!;
    if (p.includes('://') || vistas.has(p)) continue;
    vistas.add(p);
    try {
      await fs.access(path.join(grafo.raiz, p));
      reclamos.push({ tipo: 'ruta_archivo', dicho: p, realidad: 'existe', coincide: true });
    } catch {
      reclamos.push({ tipo: 'ruta_archivo', dicho: p, realidad: 'NO existe en el proyecto', coincide: false });
    }
  }

  // 3) Cifras de pruebas declaradas vs. casos contados en archivos de prueba.
  for (const m of contenido.matchAll(/(\d+)\s+(?:pruebas|tests)\b/gi)) {
    const dicho = Number(m[1]);
    reclamos.push({
      tipo: 'cifra_pruebas',
      dicho: `${dicho} pruebas`,
      realidad: `${casosPrueba} casos de prueba detectados en archivos`,
      coincide: Math.abs(dicho - casosPrueba) <= Math.max(1, Math.round(casosPrueba * 0.05)),
    });
  }

  return { archivo: archivoDocs, reclamos, desajustes: reclamos.filter((r) => !r.coincide).length };
}

export async function diagnosticarRescate(grafo: Grafo): Promise<Diagnostico> {
  // Conteo real de casos de prueba (se relee el archivo porque el grafo guarda metadatos, no el texto).
  let casosPrueba = 0;
  for (const a of grafo.archivos) {
    if (!a.esPrueba) continue;
    try {
      const texto = await fs.readFile(path.join(grafo.raiz, a.ruta), 'utf8');
      casosPrueba += (texto.match(/^\s*(?:it|test)(?:\.\w+)?\s*\(/gm) ?? []).length;
    } catch {
      /* archivo ilegible: se ignora para el conteo */
    }
  }
  // ── Arquitectura reconstruida ──
  const porModulo = new Map<string, ModuloReconstruido>();
  for (const a of grafo.archivos) {
    if (!porModulo.has(a.modulo)) {
      porModulo.set(a.modulo, { modulo: a.modulo, proposito: propositoDe(a.modulo), archivos: 0, lineas: 0, exports: 0, rutas: 0, pruebas: 0 });
    }
    const m = porModulo.get(a.modulo)!;
    m.archivos++;
    m.lineas += a.lineas;
    m.exports += a.exports.length;
    m.rutas += a.rutas.length;
    if (a.esPrueba) m.pruebas++;
  }
  const modulos = [...porModulo.values()].sort((a, b) => b.lineas - a.lineas);

  // ── Huecos ──
  const huecos: Hueco[] = [];
  for (const a of grafo.archivos) {
    if (a.esPrueba) continue; // el ruido de TODO en pruebas no aporta al plan
    for (const m of a.marcadores) {
      if (m.tipo === 'STUB') {
        huecos.push({ tipo: 'stub', archivo: a.ruta, linea: m.linea, detalle: m.texto, gravedad: 'alta' });
      } else if (m.tipo === 'FIXME') {
        huecos.push({ tipo: 'pendiente', archivo: a.ruta, linea: m.linea, detalle: `FIXME: ${m.texto}`, gravedad: 'media' });
      } else if (m.tipo === 'TODO') {
        huecos.push({ tipo: 'pendiente', archivo: a.ruta, linea: m.linea, detalle: `TODO: ${m.texto}`, gravedad: 'media' });
      } else {
        huecos.push({ tipo: 'pendiente', archivo: a.ruta, linea: m.linea, detalle: `${m.tipo}: ${m.texto}`, gravedad: 'baja' });
      }
    }
    const lineasUtiles = a.lineas;
    if (lineasUtiles <= 2 || a.bytes <= 40) {
      huecos.push({ tipo: 'archivo_vacio', archivo: a.ruta, detalle: 'archivo sin contenido útil', gravedad: 'media' });
    }
  }

  const desconectados = archivosDesconectados(grafo);
  for (const d of desconectados) {
    huecos.push({ tipo: 'desconectado', archivo: d.archivo, detalle: d.motivo, gravedad: d.rutas > 0 ? 'alta' : 'media' });
  }

  const duplicados = funcionesDuplicadas(grafo);
  for (const d of duplicados) {
    const nombres = [...new Set(d.apariciones.map((a) => a.nombre))].join(', ');
    huecos.push({
      tipo: 'duplicado',
      archivo: d.apariciones[0]!.archivo,
      linea: d.apariciones[0]!.linea,
      detalle: `función duplicada (${nombres}) en: ${d.apariciones.map((a) => `${a.archivo}:${a.linea}`).join(' · ')}`,
      gravedad: 'media',
    });
  }
  const repetidos = exportsRepetidos(grafo);
  for (const r of repetidos.slice(0, 10)) {
    huecos.push({
      tipo: 'duplicado',
      archivo: r.archivos[0]!,
      detalle: `el nombre exportado "${r.nombre}" se define en ${r.archivos.length} archivos: ${r.archivos.join(', ')}`,
      gravedad: 'baja',
    });
  }

  const documentacion = await compararDocs(grafo, casosPrueba);
  for (const r of documentacion.reclamos.filter((x) => !x.coincide)) {
    huecos.push({ tipo: 'documentacion', archivo: documentacion.archivo ?? 'README.md', detalle: `la documentación dice "${r.dicho}" pero ${r.realidad}`, gravedad: 'baja' });
  }

  // ── Plan ordenado por impacto (dependientes + gravedad) y esfuerzo ──
  const nivel: Record<Hueco['gravedad'], number> = { alta: 3, media: 2, baja: 1 };
  const iter = huecos.map((h) => {
    const dependientes = grafo.importadoPor.get(h.archivo)?.size ?? 0;
    const tamano = grafo.porRuta.get(h.archivo)?.lineas ?? 0;
    const esfuerzo: ItemPlan['esfuerzo'] = tamano > 300 ? 'L' : tamano > 120 ? 'M' : 'S';
    const impacto = nivel[h.gravedad] * 2 + dependientes + (h.tipo === 'desconectado' && h.detalle.includes('ruta(s)') ? 2 : 0);
    const tituloPorTipo: Record<Hueco['tipo'], string> = {
      stub: 'Implementar código que hoy lanza "no implementado"',
      pendiente: 'Resolver pendiente marcado en el código',
      archivo_vacio: 'Completar o eliminar archivo vacío',
      duplicado: 'Unificar código duplicado',
      desconectado: 'Conectar o eliminar módulo huérfano',
      documentacion: 'Alinear la documentación con la realidad',
    };
    return {
      titulo: `${tituloPorTipo[h.tipo]} — ${h.archivo}${h.linea ? `:${h.linea}` : ''}`,
      detalle: h.detalle,
      archivo: h.archivo,
      impacto,
      esfuerzo,
    };
  });
  const plan: ItemPlan[] = iter
    .sort((a, b) => b.impacto - a.impacto || (a.esfuerzo < b.esfuerzo ? -1 : a.esfuerzo > b.esfuerzo ? 1 : 0) || (a.archivo ?? '').localeCompare(b.archivo ?? ''))
    .slice(0, 40)
    .map((p, idx) => ({ orden: idx + 1, ...p }));

  const resumen = {
    pendientes: huecos.filter((h) => h.tipo === 'pendiente').length,
    stubs: huecos.filter((h) => h.tipo === 'stub').length,
    vacios: huecos.filter((h) => h.tipo === 'archivo_vacio').length,
    desconectados: desconectados.length,
    duplicados: huecos.filter((h) => h.tipo === 'duplicado').length,
    desajustes_docs: documentacion.desajustes,
    total_huecos: huecos.length,
  };

  // ── Veredicto ──
  const graves = resumen.stubs + desconectados.filter((d) => d.rutas > 0).length + resumen.vacios;
  const densidad = grafo.totalLineas > 0 ? resumen.total_huecos / grafo.totalLineas : 0;
  const estado: Diagnostico['estado'] =
    resumen.total_huecos === 0
      ? 'sano'
      : graves >= 3 || densidad > 0.05
        ? 'dañado'
        : graves >= 1 || (resumen.total_huecos >= 5 && densidad > 0.02)
          ? 'abandonado'
          : 'en obra';
  const veredicto =
    estado === 'sano'
      ? `El proyecto está sano: arquitectura completa, sin huecos detectables y documentación alineada (${grafo.archivos.length} archivos, ${grafo.rutas.length} rutas).`
      : `Proyecto "${estado}": ${grafo.archivos.length} archivos, ${grafo.rutas.length} rutas activas y ${resumen.total_huecos} huecos (${resumen.stubs} sin implementar, ${resumen.desconectados} desconectados, ${resumen.vacios} vacíos, ${resumen.desajustes_docs} desajustes de documentación). El plan de abajo está ordenado por lo que más desbloquea primero.`;

  return {
    raiz: grafo.raiz,
    proyecto: grafo.nombre,
    esRepoGit: await existeEn(grafo.raiz, '.git'),
    veredicto,
    estado,
    arquitectura: {
      modulos,
      entradas: grafo.entradas,
      rutas: grafo.rutas.length,
      pruebas: { archivos: grafo.archivos.filter((a) => a.esPrueba).length, casos: casosPrueba },
      servicios: grafo.servicios.slice(0, 10).map((s) => ({ paquete: s.paquete, descripcion: s.descripcion, usos: s.usos })),
      totalArchivos: grafo.archivos.length,
      totalLineas: grafo.totalLineas,
    },
    huecos,
    documentacion,
    plan,
    resumen,
  };
}

async function existeEn(raiz: string, nombre: string): Promise<boolean> {
  try {
    await fs.access(path.join(raiz, nombre));
    return true;
  } catch {
    return false;
  }
}

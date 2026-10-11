/**
 * GEMELO VIVO — estado en movimiento del mapa del proyecto.
 *
 * A diferencia del informe estático (mapa_proyecto), esta capa mantiene el
 * estado del proyecto entre revisiones y detecta QUÉ CAMBIÓ: archivos nuevos,
 * rutas, huérfanos, pendientes y duplicados. Es lo que hace del mapa un
 * gemelo digital «vivo» y no una foto.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import {
  analizarProyecto,
  archivosDesconectados,
  DIRS_IGNORADOS,
  funcionesDuplicadas,
  type Grafo,
} from './grafo.js';

export interface ArchivoVivo {
  ruta: string;
  modulo: string;
  lineas: number;
  bytes: number;
  es_prueba: boolean;
  es_entrada: boolean;
  rutas: { metodo: string; ruta: string; linea: number }[];
  imports: string[];
  dependientes: string[];
  exports: string[];
  paquetes: string[];
  marcadores: { tipo: string; linea: number; texto: string }[];
  huerfano: boolean;
  duplicado: boolean;
}

export interface EstadoVivo {
  raiz: string;
  proyecto: string;
  version: string | null;
  revision: number;
  generado_en: string;
  huella: string;
  totales: {
    archivos: number;
    lineas: number;
    rutas: number;
    pruebas: number;
    entradas: number;
    huerfanos: number;
    duplicados: number;
    pendientes: number;
    servicios: number;
  };
  modulos: { modulo: string; archivos: number; lineas: number; rutas: number }[];
  aristas: { desde: string; hacia: string; peso: number }[];
  aristas_modulo: { desde: string; hacia: string; peso: number }[];
  archivos: ArchivoVivo[];
  entradas: string[];
  servicios: { paquete: string; descripcion: string; usos: number }[];
}

export interface CambioVivo {
  tipo:
    | 'archivo_nuevo'
    | 'archivo_eliminado'
    | 'lineas'
    | 'ruta_nueva'
    | 'ruta_eliminada'
    | 'huerfano_nuevo'
    | 'huerfano_resuelto'
    | 'pendiente_nuevo'
    | 'pendiente_resuelto'
    | 'duplicado_nuevo'
    | 'sin_cambios';
  detalle: string;
  archivo?: string;
}

interface Cache {
  huella: string;
  revision: number;
  estado: EstadoVivo;
}

const cache = new Map<string, Cache>();

const DIRS_CODIGO = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs', '.mts', '.cts']);

/** Huella barata (mtime+size) de los archivos de código: detecta cambios sin re-analizar. */
async function huellaDe(raiz: string): Promise<string> {
  const acumulado: string[] = [];
  async function recorrer(dir: string): Promise<void> {
    let entradas;
    try {
      entradas = await fs.readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entradas.sort((a, b) => a.name.localeCompare(b.name))) {
      if (e.isDirectory()) {
        if (DIRS_IGNORADOS.has(e.name) || e.name.startsWith('.')) continue;
        await recorrer(path.join(dir, e.name));
      } else if (e.isFile() && DIRS_CODIGO.has(path.extname(e.name).toLowerCase())) {
        try {
          const st = await fs.stat(path.join(dir, e.name));
          acumulado.push(`${path.relative(raiz, path.join(dir, e.name))}:${st.size}:${Math.round(st.mtimeMs)}`);
        } catch {
          /* ilegible */
        }
      }
    }
  }
  await recorrer(raiz);
  return acumulado.join('|');
}

function estadoDe(grafo: Grafo, revision: number, huella: string): EstadoVivo {
  const huerfanos = new Set(archivosDesconectados(grafo).map((d) => d.archivo));
  const duplicados = new Set(funcionesDuplicadas(grafo).flatMap((d) => d.apariciones.map((a) => a.archivo)));

  const porModulo = new Map<string, { modulo: string; archivos: number; lineas: number; rutas: number }>();
  for (const a of grafo.archivos) {
    if (!porModulo.has(a.modulo)) porModulo.set(a.modulo, { modulo: a.modulo, archivos: 0, lineas: 0, rutas: 0 });
    const m = porModulo.get(a.modulo)!;
    m.archivos++;
    m.lineas += a.lineas;
    m.rutas += a.rutas.length;
  }

  const archivos: ArchivoVivo[] = grafo.archivos.map((a) => ({
    ruta: a.ruta,
    modulo: a.modulo,
    lineas: a.lineas,
    bytes: a.bytes,
    es_prueba: a.esPrueba,
    es_entrada: a.esEntrada,
    rutas: a.rutas,
    imports: a.imports.filter((i) => i.resuelto).map((i) => i.resuelto!),
    dependientes: [...(grafo.importadoPor.get(a.ruta) ?? [])],
    exports: a.exports.map((e) => e.nombre),
    paquetes: a.paquetes,
    marcadores: a.marcadores.map((m) => ({ tipo: m.tipo, linea: m.linea, texto: m.texto })),
    huerfano: huerfanos.has(a.ruta),
    duplicado: duplicados.has(a.ruta),
  }));

  const pendientes = archivos.reduce(
    (acc, a) => acc + a.marcadores.filter((m) => m.tipo === 'TODO' || m.tipo === 'FIXME' || m.tipo === 'STUB').length,
    0,
  );
  const aristas: EstadoVivo['aristas'] = [];
  const aristasModulo = new Map<string, number>();
  for (const [desde, destinos] of grafo.importa) {
    const md = grafo.porRuta.get(desde)?.modulo ?? '—';
    for (const destino of destinos) {
      aristas.push({ desde, hacia: destino, peso: 1 });
      const mh = grafo.porRuta.get(destino)?.modulo ?? '—';
      if (md === mh) continue;
      const clave = `${md}|${mh}`;
      aristasModulo.set(clave, (aristasModulo.get(clave) ?? 0) + 1);
    }
  }

  return {
    raiz: grafo.raiz,
    proyecto: grafo.nombre,
    version: grafo.paquete?.version ?? null,
    revision,
    generado_en: grafo.generadoEn,
    huella,
    totales: {
      archivos: grafo.archivos.length,
      lineas: grafo.totalLineas,
      rutas: grafo.rutas.length,
      pruebas: grafo.archivos.filter((a) => a.esPrueba).length,
      entradas: grafo.entradas.length,
      huerfanos: huerfanos.size,
      duplicados: duplicados.size,
      pendientes,
      servicios: grafo.servicios.length,
    },
    modulos: [...porModulo.values()].sort((a, b) => b.lineas - a.lineas),
    aristas: aristas.map((a) => ({ ...a })),
    aristas_modulo: [...aristasModulo.entries()].map(([clave, peso]) => {
      const [desde, hacia] = clave.split('|');
      return { desde: desde!, hacia: hacia!, peso };
    }),
    archivos,
    entradas: grafo.entradas,
    servicios: grafo.servicios.map((s) => ({ paquete: s.paquete, descripcion: s.descripcion, usos: s.usos })),
  };
}

/** Compara dos estados y describe los cambios en lenguaje humano. */
export function calcularCambios(anterior: EstadoVivo | undefined, actual: EstadoVivo): CambioVivo[] {
  if (!anterior) return [];
  const cambios: CambioVivo[] = [];

  const rutasAntes = new Map(anterior.archivos.map((a) => [a.ruta, a]));
  const rutasAhora = new Map(actual.archivos.map((a) => [a.ruta, a]));

  for (const [ruta, ahora] of rutasAhora) {
    const antes = rutasAntes.get(ruta);
    if (!antes) {
      cambios.push({ tipo: 'archivo_nuevo', archivo: ruta, detalle: `archivo nuevo: ${ruta} (${ahora.lineas} líneas)` });
      for (const r of ahora.rutas) cambios.push({ tipo: 'ruta_nueva', archivo: ruta, detalle: `ruta nueva en ${ruta}: ${r.metodo} ${r.ruta}` });
      const p = ahora.marcadores.filter((m) => m.tipo === 'TODO' || m.tipo === 'FIXME' || m.tipo === 'STUB').length;
      if (p > 0) cambios.push({ tipo: 'pendiente_nuevo', archivo: ruta, detalle: `${ruta}: ${p} pendiente(s) nuevo(s)` });
      if (ahora.huerfano) cambios.push({ tipo: 'huerfano_nuevo', archivo: ruta, detalle: `${ruta} nació desconectado (nadie lo importa)` });
      if (ahora.duplicado) cambios.push({ tipo: 'duplicado_nuevo', archivo: ruta, detalle: `${ruta}: se detectó una función duplicada` });
      continue;
    }
    const delta = ahora.lineas - antes.lineas;
    if (delta !== 0) cambios.push({ tipo: 'lineas', archivo: ruta, detalle: `${ruta}: ${delta > 0 ? '+' : ''}${delta} líneas` });

    const rutasPrevias = new Set(antes.rutas.map((r) => `${r.metodo} ${r.ruta}`));
    for (const r of ahora.rutas) {
      if (!rutasPrevias.has(`${r.metodo} ${r.ruta}`)) cambios.push({ tipo: 'ruta_nueva', archivo: ruta, detalle: `ruta nueva en ${ruta}: ${r.metodo} ${r.ruta}` });
    }
    const rutasActuales = new Set(ahora.rutas.map((r) => `${r.metodo} ${r.ruta}`));
    for (const r of antes.rutas) {
      if (!rutasActuales.has(`${r.metodo} ${r.ruta}`)) cambios.push({ tipo: 'ruta_eliminada', archivo: ruta, detalle: `ruta eliminada de ${ruta}: ${r.metodo} ${r.ruta}` });
    }

    if (!antes.huerfano && ahora.huerfano) cambios.push({ tipo: 'huerfano_nuevo', archivo: ruta, detalle: `${ruta} quedó desconectado (nadie lo importa)` });
    if (antes.huerfano && !ahora.huerfano) cambios.push({ tipo: 'huerfano_resuelto', archivo: ruta, detalle: `${ruta} ya está conectado` });
    if (!antes.duplicado && ahora.duplicado) cambios.push({ tipo: 'duplicado_nuevo', archivo: ruta, detalle: `${ruta}: se detectó una función duplicada` });

    const conteo = (a: ArchivoVivo) => a.marcadores.filter((m) => m.tipo === 'TODO' || m.tipo === 'FIXME' || m.tipo === 'STUB').length;
    const antesP = conteo(antes);
    const ahoraP = conteo(ahora);
    if (ahoraP > antesP) cambios.push({ tipo: 'pendiente_nuevo', archivo: ruta, detalle: `${ruta}: ${ahoraP - antesP} pendiente(s) nuevo(s)` });
    if (ahoraP < antesP) cambios.push({ tipo: 'pendiente_resuelto', archivo: ruta, detalle: `${ruta}: ${antesP - ahoraP} pendiente(s) resuelto(s)` });
  }
  for (const [ruta] of rutasAntes) {
    if (!rutasAhora.has(ruta)) cambios.push({ tipo: 'archivo_eliminado', archivo: ruta, detalle: `archivo eliminado: ${ruta}` });
  }

  if (cambios.length === 0) cambios.push({ tipo: 'sin_cambios', detalle: 'sin cambios desde la última revisión' });
  return cambios;
}

export interface ResultadoVivo {
  estado: EstadoVivo;
  cambios: CambioVivo[];
  hubo_cambios: boolean;
  cacheado: boolean;
}

/** Estado vivo de un directorio: re-analiza solo cuando la huella cambia. */
export async function obtenerEstadoVivo(raiz: string, opciones?: { forzar?: boolean }): Promise<ResultadoVivo> {
  const huella = await huellaDe(raiz);
  const previo = cache.get(raiz);

  if (!opciones?.forzar && previo && previo.huella === huella) {
    return { estado: previo.estado, cambios: [], hubo_cambios: false, cacheado: true };
  }

  const grafo = await analizarProyecto(raiz);
  const revision = (previo?.revision ?? 0) + 1;
  const estado = estadoDe(grafo, revision, huella);

  if (!previo) {
    cache.set(raiz, { huella, revision, estado });
    return { estado, cambios: [], hubo_cambios: false, cacheado: false };
  }

  const cambios = calcularCambios(previo.estado, estado);
  cache.set(raiz, { huella, revision, estado });
  return { estado, cambios, hubo_cambios: cambios.some((c) => c.tipo !== 'sin_cambios'), cacheado: false };
}

/** Para pruebas: limpia la caché en memoria. */
export function limpiarCacheVivo(raiz?: string): void {
  if (raiz) cache.delete(raiz);
  else cache.clear();
}

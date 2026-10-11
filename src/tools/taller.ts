/**
 * Módulo «taller» (fase 4A) — GEMELO + RESCATE.
 *
 * Analiza proyectos de código (propios o ajenos) dentro de las rutas
 * autorizadas. Todo es local: no sube código a ningún servicio.
 *   · mapa_proyecto        → el gemelo digital (mapa vivo del proyecto)
 *   · impacto_calcular     → qué se rompe si cambio esto
 *   · rescate_diagnostico  → reconstrucción + huecos + plan para retomar
 *   · pendientes_listar    → lo que falta, ordenado por impacto
 */
import { z } from 'zod';
import fs from 'node:fs/promises';
import path from 'node:path';
import type { ServerCtx } from '../context.js';
import type { ToolDef } from '../lib/result.js';
import { analizarProyecto, calcularImpacto, archivosDesconectados, funcionesDuplicadas, type Grafo } from '../lib/grafo.js';
import { diagnosticarRescate } from '../lib/rescate.js';

function resolverDirectorio(ctx: ServerCtx, dir?: string): string {
  const candidato = path.resolve(dir ?? ctx.config.allowedGitRoots[0] ?? process.cwd());
  const permitido = ctx.config.allowedGitRoots.some(
    (root) => candidato === root || candidato.startsWith(`${root}${path.sep}`),
  );
  if (!permitido) {
    throw new Error(
      `Directorio fuera de las rutas permitidas: ${candidato}. Configura ALLOWED_GIT_ROOTS con las rutas autorizadas.`,
    );
  }
  return candidato;
}

async function guardarInforme(ctx: ServerCtx, nombre: string, extension: string, contenido: string): Promise<string> {
  const dir = path.join(ctx.config.dataDir, 'taller');
  await fs.mkdir(dir, { recursive: true });
  const sello = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const archivo = path.join(dir, `${nombre}-${sello}.${extension}`);
  await fs.writeFile(archivo, contenido, 'utf8');
  return archivo;
}

function resumenGrafo(grafo: Grafo) {
  const pruebas = grafo.archivos.filter((a) => a.esPrueba);
  const porModulo = new Map<string, { modulo: string; archivos: number; lineas: number; rutas: number; exports: number }>();
  for (const a of grafo.archivos) {
    if (!porModulo.has(a.modulo)) porModulo.set(a.modulo, { modulo: a.modulo, archivos: 0, lineas: 0, rutas: 0, exports: 0 });
    const m = porModulo.get(a.modulo)!;
    m.archivos++;
    m.lineas += a.lineas;
    m.rutas += a.rutas.length;
    m.exports += a.exports.length;
  }
  const desconectados = archivosDesconectados(grafo);
  const duplicados = funcionesDuplicadas(grafo);
  return {
    raiz: grafo.raiz,
    proyecto: grafo.nombre,
    version: grafo.paquete?.version,
    generado_en: grafo.generadoEn,
    totales: {
      archivos_codigo: grafo.archivos.length,
      lineas: grafo.totalLineas,
      rutas: grafo.rutas.length,
      pruebas: pruebas.length,
      entradas: grafo.entradas.length,
      desconectados: desconectados.length,
      funciones_duplicadas: duplicados.length,
    },
    modulos: [...porModulo.values()].sort((a, b) => b.lineas - a.lineas),
    entradas: grafo.entradas,
    servicios: grafo.servicios.slice(0, 12),
    rutas: grafo.rutas.map((r) => ({ metodo: r.metodo, ruta: r.ruta, archivo: r.archivo, linea: r.linea })),
    desconectados,
    otros_archivos_por_extension: grafo.otrosArchivos,
  };
}

function markdownGrafo(grafo: Grafo): string {
  const r = resumenGrafo(grafo);
  const lineas: string[] = [];
  lineas.push(`# Mapa del proyecto — ${grafo.nombre}`);
  lineas.push('');
  lineas.push(`> Generado por GEMELO (mcp-suite-mx) el ${grafo.generadoEn}. Raíz: \`${grafo.raiz}\`.`);
  lineas.push('');
  lineas.push('## Resumen');
  lineas.push('');
  lineas.push('| Indicador | Valor |');
  lineas.push('|---|---|');
  lineas.push(`| Archivos de código | ${r.totales.archivos_codigo} |`);
  lineas.push(`| Líneas | ${r.totales.lineas} |`);
  lineas.push(`| Rutas HTTP detectadas | ${r.totales.rutas} |`);
  lineas.push(`| Archivos de prueba | ${r.totales.pruebas} |`);
  lineas.push(`| Entradas | ${r.totales.entradas} |`);
  lineas.push(`| Desconectados | ${r.totales.desconectados} |`);
  lineas.push(`| Funciones duplicadas | ${r.totales.funciones_duplicadas} |`);
  lineas.push('');
  lineas.push('## Módulos');
  lineas.push('');
  lineas.push('| Módulo | Archivos | Líneas | Rutas | Exportados |');
  lineas.push('|---|---:|---:|---:|---:|');
  for (const m of r.modulos) lineas.push(`| \`${m.modulo}\` | ${m.archivos} | ${m.lineas} | ${m.rutas} | ${m.exports} |`);
  lineas.push('');
  lineas.push('## Dependencias entre módulos');
  lineas.push('');
  const aristas = new Map<string, number>();
  for (const [desde, destinos] of grafo.importa) {
    const md = grafo.porRuta.get(desde)?.modulo ?? '—';
    for (const destino of destinos) {
      const mh = grafo.porRuta.get(destino)?.modulo ?? '—';
      if (md === mh) continue;
      const clave = `${md} → ${mh}`;
      aristas.set(clave, (aristas.get(clave) ?? 0) + 1);
    }
  }
  if (aristas.size > 0) {
    lineas.push('```mermaid');
    lineas.push('graph LR');
    for (const [clave, peso] of [...aristas.entries()].sort()) {
      const [a, b] = clave.split(' → ');
      const id = (s: string) => s.replace(/[^A-Za-z0-9_]/g, '_');
      lineas.push(`  ${id(a!)}["${a}"] -->|${peso}| ${id(b!)}["${b}"]`);
    }
    lineas.push('```');
  } else {
    lineas.push('_Sin dependencias cruzadas entre módulos._');
  }
  lineas.push('');
  lineas.push('## Rutas HTTP');
  lineas.push('');
  if (grafo.rutas.length === 0) lineas.push('_No se detectaron rutas._');
  else {
    lineas.push('| Método | Ruta | Archivo | Línea |');
    lineas.push('|---|---|---|---:|');
    for (const rt of grafo.rutas.slice(0, 60)) lineas.push(`| ${rt.metodo} | \`${rt.ruta}\` | \`${rt.archivo}\` | ${rt.linea} |`);
  }
  lineas.push('');
  lineas.push('## Servicios y dependencias externas');
  lineas.push('');
  if (grafo.servicios.length === 0) lineas.push('_Solo dependencias internas._');
  else {
    lineas.push('| Paquete | Uso | Archivos |');
    lineas.push('|---|---|---:|');
    for (const s of grafo.servicios) lineas.push(`| \`${s.paquete}\` | ${s.descripcion} | ${s.usos} |`);
  }
  lineas.push('');
  return lineas.join('\n');
}

function svgGrafo(grafo: Grafo): string {
  const modulos = [...new Set(grafo.archivos.map((a) => a.modulo))].sort();
  const aristas = new Map<string, number>();
  for (const [desde, destinos] of grafo.importa) {
    const md = grafo.porRuta.get(desde)?.modulo ?? '—';
    for (const destino of destinos) {
      const mh = grafo.porRuta.get(destino)?.modulo ?? '—';
      if (md === mh) continue;
      const clave = `${md}|${mh}`;
      aristas.set(clave, (aristas.get(clave) ?? 0) + 1);
    }
  }
  const cols = Math.max(1, Math.ceil(Math.sqrt(modulos.length)));
  const ancho = 220;
  const alto = 76;
  const gapX = 70;
  const gapY = 56;
  const margen = 40;
  const filas = Math.ceil(modulos.length / cols);
  const W = margen * 2 + cols * ancho + (cols - 1) * gapX;
  const H = margen * 2 + filas * alto + (filas - 1) * gapY + 30;
  const pos = new Map<string, { x: number; y: number }>();
  modulos.forEach((m, i) => {
    const col = i % cols;
    const fila = Math.floor(i / cols);
    pos.set(m, { x: margen + col * (ancho + gapX), y: margen + 30 + fila * (alto + gapY) });
  });
  const conteo = new Map(modulos.map((m) => [m, grafo.archivos.filter((a) => a.modulo === m).length]));
  const out: string[] = [];
  out.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">`);
  out.push(`<rect width="${W}" height="${H}" fill="#0d1420"/>`);
  out.push(`<text x="${margen}" y="${margen - 8}" fill="#e6edf3" font-family="ui-monospace,monospace" font-size="16">Mapa del proyecto — ${escXml(grafo.nombre)} (${grafo.archivos.length} archivos)</text>`);
  for (const [clave, peso] of aristas) {
    const [a, b] = clave.split('|');
    const pa = pos.get(a!);
    const pb = pos.get(b!);
    if (!pa || !pb) continue;
    const x1 = pa.x + ancho / 2;
    const y1 = pa.y + alto / 2;
    const x2 = pb.x + ancho / 2;
    const y2 = pb.y + alto / 2;
    out.push(`<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="#3d5a80" stroke-width="${Math.min(1 + peso / 3, 5)}" opacity="0.55"/>`);
    out.push(`<text x="${(x1 + x2) / 2}" y="${(y1 + y2) / 2 - 4}" fill="#7fa3c8" font-family="ui-monospace,monospace" font-size="11">${peso}</text>`);
  }
  for (const m of modulos) {
    const p = pos.get(m)!;
    out.push(`<rect x="${p.x}" y="${p.y}" width="${ancho}" height="${alto}" rx="10" fill="#152033" stroke="#4ec3e0" stroke-width="1.5"/>`);
    out.push(`<text x="${p.x + 14}" y="${p.y + 30}" fill="#e6edf3" font-family="ui-monospace,monospace" font-size="14">${escXml(m)}</text>`);
    out.push(`<text x="${p.x + 14}" y="${p.y + 52}" fill="#7fa3c8" font-family="ui-monospace,monospace" font-size="11">${conteo.get(m)} archivos</text>`);
  }
  out.push('</svg>');
  return out.join('\n');
}

function escXml(s: string): string {
  return s.replace(/[<>&"']/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&#39;' })[c]!);
}

function diagnosticoMarkdown(d: Awaited<ReturnType<typeof diagnosticarRescate>>): string {
  const l: string[] = [];
  l.push(`# Diagnóstico de rescate — ${d.proyecto}`);
  l.push('');
  l.push(`**Veredicto:** ${d.veredicto}`);
  l.push('');
  l.push(`Raíz analizada: \`${d.raiz}\` · estado: **${d.estado}** · repositorio git: ${d.esRepoGit ? 'sí' : 'no'}`);
  l.push('');
  l.push('## Arquitectura reconstruida');
  l.push('');
  l.push('| Módulo | Propósito | Archivos | Líneas | Rutas |');
  l.push('|---|---|---:|---:|---:|');
  for (const m of d.arquitectura.modulos) l.push(`| \`${m.modulo}\` | ${m.proposito} | ${m.archivos} | ${m.lineas} | ${m.rutas} |`);
  l.push('');
  l.push(`Entradas: ${d.arquitectura.entradas.map((e) => `\`${e}\``).join(', ') || '—'} · Rutas: ${d.arquitectura.rutas} · Pruebas: ${d.arquitectura.pruebas.casos} casos en ${d.arquitectura.pruebas.archivos} archivos.`);
  l.push('');
  l.push('## Huecos detectados');
  l.push('');
  l.push(`Pendientes: ${d.resumen.pendientes} · Sin implementar: ${d.resumen.stubs} · Vacíos: ${d.resumen.vacios} · Desconectados: ${d.resumen.desconectados} · Duplicados: ${d.resumen.duplicados} · Desajustes de documentación: ${d.resumen.desajustes_docs}`);
  l.push('');
  for (const h of d.huecos.slice(0, 40)) {
    l.push(`- **${h.tipo}** (${h.gravedad}) — \`${h.archivo}${h.linea ? `:${h.linea}` : ''}\`: ${h.detalle}`);
  }
  l.push('');
  l.push('## Plan de terminación (ordenado por impacto)');
  l.push('');
  for (const p of d.plan) {
    l.push(`${p.orden}. **${p.titulo}** — impacto ${p.impacto}, esfuerzo ${p.esfuerzo}. ${p.detalle}`);
  }
  l.push('');
  return l.join('\n');
}

export function tallerToolDefs(defs: ToolDef[], ctx: ServerCtx): void {
  const push = (d: ToolDef) => void defs.push(d);

  push({
    module: 'taller',
    name: 'mapa_proyecto',
    title: 'Mapa vivo del proyecto (gemelo digital)',
    description:
      'Analiza un proyecto de código y construye su gemelo digital: módulos, archivos, dependencias entre módulos, rutas HTTP, servicios externos, entradas, pruebas, componentes desconectados y funciones duplicadas. Sirve para conocer un proyecto sin leerlo todo, para documentarlo automáticamente y como base de los análisis de impacto. Formatos: resumen (JSON compacto), markdown (informe con diagrama Mermaid), svg (diagrama visual) o json (detallado). Opcionalmente guarda el informe en data/taller/.',
    readOnly: true,
    inputSchema: {
      directorio: z.string().optional().describe('Ruta del proyecto a analizar. Debe estar dentro de ALLOWED_GIT_ROOTS. Si se omite, usa la primera ruta autorizada.'),
      formato: z.enum(['resumen', 'markdown', 'svg', 'json']).optional().describe('Formato de salida (por defecto: resumen).'),
      guardar: z.boolean().optional().describe('Si es true, guarda el informe en data/taller/ del servidor.'),
    },
    handler: async (args: { directorio?: string; formato?: 'resumen' | 'markdown' | 'svg' | 'json'; guardar?: boolean }) => {
      const raiz = resolverDirectorio(ctx, args.directorio);
      const grafo = await analizarProyecto(raiz);
      const formato = args.formato ?? 'resumen';

      let salida: unknown;
      let extension = 'json';
      if (formato === 'markdown') {
        salida = markdownGrafo(grafo);
        extension = 'md';
      } else if (formato === 'svg') {
        salida = svgGrafo(grafo);
        extension = 'svg';
      } else if (formato === 'json') {
        salida = {
          raiz: grafo.raiz,
          nombre: grafo.nombre,
          generado_en: grafo.generadoEn,
          paquete: grafo.paquete,
          entradas: grafo.entradas,
          total_lineas: grafo.totalLineas,
          archivos: grafo.archivos.map((a) => ({
            ruta: a.ruta,
            modulo: a.modulo,
            lineas: a.lineas,
            es_prueba: a.esPrueba,
            es_entrada: a.esEntrada,
            imports: a.imports.filter((i) => i.resuelto).map((i) => ({ de: i.resuelto, linea: i.linea })),
            exports: a.exports.map((e) => e.nombre),
            rutas: a.rutas,
            paquetes: a.paquetes,
            marcadores: a.marcadores,
          })),
          servicios: grafo.servicios,
        };
      } else {
        salida = resumenGrafo(grafo);
      }

      const extra: Record<string, unknown> = {};
      if (args.guardar) {
        const contenido = typeof salida === 'string' ? salida : JSON.stringify(salida, null, 2);
        extra.guardado_en = await guardarInforme(ctx, `mapa-${path.basename(raiz)}`, extension, contenido);
      }
      if (formato === 'resumen' || formato === 'json') {
        return { ...(salida as object), ...extra };
      }
      return { formato, ...extra, contenido: salida };
    },
  });

  push({
    module: 'taller',
    name: 'impacto_calcular',
    title: 'Calcular el impacto de un cambio',
    description:
      'Dado un archivo (ej. src/lib/cfdi.ts) o un símbolo exportado (ej. calcularImpacto), calcula qué se rompería al modificarlo: dependientes directos y transitivos con evidencia (archivo:línea del import), rutas HTTP afectadas, pruebas que lo cubren, servicios externos involucrados, nivel de riesgo y comando sugerido para verificar. Úsalo ANTES de tocar código compartido.',
    readOnly: true,
    inputSchema: {
      objetivo: z.string().describe('Ruta de archivo (src/lib/cfdi.ts) o nombre de símbolo exportado (generarBorradorCfdi).'),
      directorio: z.string().optional().describe('Ruta del proyecto (debe estar en ALLOWED_GIT_ROOTS).'),
      profundidad: z.number().int().min(1).max(6).optional().describe('Niveles de dependencias transitivas a explorar (por defecto 3).'),
    },
    handler: async (args: { objetivo: string; directorio?: string; profundidad?: number }) => {
      const raiz = resolverDirectorio(ctx, args.directorio);
      const grafo = await analizarProyecto(raiz);
      const impacto = calcularImpacto(grafo, args.objetivo, args.profundidad ?? 3);
      const pruebas = impacto.pruebasAfectadas;
      const comando = pruebas.length > 0 ? `npm test -- ${pruebas.join(' ')}` : grafo.paquete?.scripts?.test ? 'npm test (suite completa: no hay pruebas ligadas directamente)' : 'no hay script de pruebas en package.json';
      return {
        ...impacto,
        detalle_objetivo: impacto.archivosObjetivo.map((f) => {
          const a = grafo.porRuta.get(f)!;
          return { archivo: f, lineas: a.lineas, exports: a.exports.map((e) => e.nombre).slice(0, 15), rutas_propias: a.rutas };
        }),
        comando_sugerido: comando,
      };
    },
  });

  push({
    module: 'taller',
    name: 'rescate_diagnostico',
    title: 'Diagnóstico de rescate de un proyecto',
    description:
      'Para retomar un proyecto abandonado o dañado sin recordar su historia: reconstruye la arquitectura desde el código, detecta lo incompleto (TODO/FIXME, código que lanza "no implementado", archivos vacíos), lo desconectado (módulos huérfanos, rutas sin montar), duplicados y desajustes entre la documentación y la realidad; y devuelve un PLAN ordenado por impacto (qué desbloquea más primero) con esfuerzo estimado. Opcionalmente guarda el informe en data/taller/.',
    readOnly: true,
    inputSchema: {
      directorio: z.string().optional().describe('Ruta del proyecto a rescatar (debe estar en ALLOWED_GIT_ROOTS).'),
      incluir_plan: z.boolean().optional().describe('Incluir el plan de terminación ordenado (por defecto true).'),
      guardar: z.boolean().optional().describe('Guardar el informe en Markdown dentro de data/taller/.'),
    },
    handler: async (args: { directorio?: string; incluir_plan?: boolean; guardar?: boolean }) => {
      const raiz = resolverDirectorio(ctx, args.directorio);
      const grafo = await analizarProyecto(raiz);
      const diagnostico = await diagnosticarRescate(grafo);
      const extra: Record<string, unknown> = {};
      if (args.guardar) extra.guardado_en = await guardarInforme(ctx, `rescate-${path.basename(raiz)}`, 'md', diagnosticoMarkdown(diagnostico));
      if (args.incluir_plan === false) {
        const { plan, ...resto } = diagnostico;
        return { ...resto, plan_omitido: true, total_items_plan: plan.length, ...extra };
      }
      return { ...diagnostico, ...extra };
    },
  });

  push({
    module: 'taller',
    name: 'pendientes_listar',
    title: 'Lista de pendientes ordenada por impacto',
    description:
      'Lista lo que falta en un proyecto (TODO, FIXME, código sin implementar y archivos vacíos) ordenado por impacto real: primero lo que está en archivos de los que dependen más cosas. Ideal para decidir por dónde continuar un proyecto a medias.',
    readOnly: true,
    inputSchema: {
      directorio: z.string().optional().describe('Ruta del proyecto (debe estar en ALLOWED_GIT_ROOTS).'),
      limite: z.number().int().min(1).max(200).optional().describe('Máximo de pendientes a devolver (por defecto 25).'),
    },
    handler: async (args: { directorio?: string; limite?: number }) => {
      const raiz = resolverDirectorio(ctx, args.directorio);
      const grafo = await analizarProyecto(raiz);
      const diagnostico = await diagnosticarRescate(grafo);
      const pendientes = diagnostico.huecos
        .filter((h) => h.tipo === 'pendiente' || h.tipo === 'stub' || h.tipo === 'archivo_vacio')
        .map((h) => ({
          ...h,
          dependientes: grafo.importadoPor.get(h.archivo)?.size ?? 0,
        }))
        .sort((a, b) => b.dependientes - a.dependientes || (a.gravedad < b.gravedad ? -1 : a.gravedad > b.gravedad ? 1 : 0))
        .slice(0, args.limite ?? 25);
      return {
        raiz,
        total: diagnostico.huecos.filter((h) => h.tipo === 'pendiente' || h.tipo === 'stub' || h.tipo === 'archivo_vacio').length,
        mostrados: pendientes.length,
        pendientes,
      };
    },
  });
}

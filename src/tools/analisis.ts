import { z } from 'zod';
import fs from 'node:fs/promises';
import path from 'node:path';
import type { ServerCtx } from '../context.js';
import type { ToolDef } from '../lib/result.js';
import { readTextFile, resolveAllowedPath } from '../lib/store.js';
import { csvToRecords, guessNumber, profileTable, toCsv, toMarkdownTable, type Table } from '../lib/csv.js';
import { cagr, cambioPct, mediaMovil, pearson, regresionLineal, resumir } from '../lib/stats.js';
import { log } from '../logger.js';

async function cargarTabla(ctx: ServerCtx, ruta: string, maxFilas: number, delimitador?: string) {
  const file = resolveAllowedPath(ctx.config.dataDir, ruta, [process.cwd()]);
  const texto = await readTextFile(file, 20_000_000);
  const tabla = csvToRecords(texto, delimitador);
  const totalFilas = tabla.rows.length;
  if (tabla.headers.length === 0) throw new Error(`El archivo "${ruta}" no tiene encabezados o está vacío.`);
  if (totalFilas > maxFilas) tabla.rows = tabla.rows.slice(0, maxFilas);
  return { file, tabla, totalFilas };
}

function columnaNumerica(tabla: Table, columna: string): { valores: number[]; indices: number[] } {
  if (!tabla.headers.includes(columna)) {
    throw new Error(`La columna "${columna}" no existe. Columnas disponibles: ${tabla.headers.join(', ')}.`);
  }
  const valores: number[] = [];
  const indices: number[] = [];
  tabla.rows.forEach((r, i) => {
    const n = guessNumber(r[columna] ?? '');
    if (n !== null) {
      valores.push(n);
      indices.push(i);
    }
  });
  if (valores.length === 0) throw new Error(`La columna "${columna}" no contiene valores numéricos.`);
  return { valores, indices };
}

const esFechaISO = (s: string) => /^\d{4}-\d{2}-\d{2}/.test(s);

export function analisisToolDefs(defs: ToolDef[], ctx: ServerCtx): void {
  const push = (d: ToolDef) => void defs.push(d);
  push({
    module: 'analisis',
    name: 'dataset_perfilar',
    title: 'Perfilar un dataset CSV',
    description:
      'Analiza un archivo CSV y devuelve la estructura del dataset: número de filas y columnas, tipo detectado por columna (numérica, fecha, texto o vacía), valores nulos, cardinalidad, min/max/media y valores más frecuentes. Es el primer paso para entender datos antes de analizarlos.',
    readOnly: true,
    inputSchema: {
      ruta: z.string().describe('Ruta del CSV, relativa al directorio de datos (ej. "datasets/ventas_2025.csv").'),
      delimitador: z.string().optional().describe('Delimitador (por defecto se detecta automáticamente: coma, punto y coma, tabulador o pipe).'),
    },
    handler: async (args: { ruta: string; delimitador?: string }) => {
      const { file, tabla, totalFilas } = await cargarTabla(ctx, args.ruta, 50_000, args.delimitador);
      const perfil = profileTable(tabla);
      return {
        archivo: path.basename(file),
        filas: totalFilas,
        columnas: tabla.headers.length,
        nombres_columnas: tabla.headers,
        muestra_filas: tabla.rows.slice(0, 5),
        perfil_columnas: perfil,
      };
    },
  });

  push({
    module: 'analisis',
    name: 'dataset_analizar_columna',
    title: 'Analizar columna numérica de un CSV',
    description:
      'Estadística descriptiva de una columna numérica de un CSV: suma, media, mediana, desviación estándar, cuartiles, mínimo, máximo y outliers por regla 1.5·IQR. Con estadisticas="tendencia" agrega regresión lineal y pronóstico; con estadisticas="completo" agrega tendencia y media móvil.',
    readOnly: true,
    inputSchema: {
      ruta: z.string().describe('Ruta del CSV en el directorio de datos.'),
      columna: z.string().describe('Nombre exacto de la columna numérica a analizar.'),
      delimitador: z.string().optional().describe('Delimitador del CSV (autodetectado por defecto).'),
      filtro_columna: z.string().optional().describe('Columna para filtrar filas antes de analizar (ej. "estado").'),
      filtro_valor: z.string().optional().describe('Valor exacto del filtro (ej. "entregado").'),
      estadisticas: z.enum(['basicas', 'tendencia', 'completo']).optional().describe('Nivel de detalle. Por defecto "basicas".'),
      ventana_media_movil: z.number().int().min(2).max(50).optional().describe('Ventana para media móvil cuando estadisticas="completo" (por defecto 3).'),
    },
    handler: async (args: any) => {
      const { file, tabla, totalFilas } = await cargarTabla(ctx, args.ruta, 100_000, args.delimitador);
      let subtabla = tabla;
      if (args.filtro_columna) {
        if (!tabla.headers.includes(args.filtro_columna)) {
          throw new Error(`La columna de filtro "${args.filtro_columna}" no existe. Disponibles: ${tabla.headers.join(', ')}.`);
        }
        if (args.filtro_valor === undefined) throw new Error('Debes indicar filtro_valor junto con filtro_columna.');
        const v = String(args.filtro_valor).toLowerCase();
        subtabla = {
          headers: tabla.headers,
          rows: tabla.rows.filter((r) => (r[args.filtro_columna] ?? '').toLowerCase() === v),
        };
      }
      const { valores } = columnaNumerica(subtabla, args.columna);
      const resumen = resumir(valores);
      const salida: Record<string, unknown> = {
        archivo: path.basename(file),
        columna: args.columna,
        filas_totales: totalFilas,
        filas_analizadas: subtabla.rows.length,
        estadisticas: resumen,
      };
      if (args.estadisticas === 'tendencia' || args.estadisticas === 'completo') {
        salida.tendencia = regresionLineal(valores);
      }
      if (args.estadisticas === 'completo') {
        const ventana = args.ventana_media_movil ?? 3;
        salida.media_movil = { ventana, valores: mediaMovil(valores, ventana) };
        salida.cambio_primero_ultimo_pct = valores.length >= 2 ? cambioPct(valores[valores.length - 1]!, valores[0]!) : null;
      }
      return salida;
    },
  });

  push({
    module: 'analisis',
    name: 'dataset_agrupar',
    title: 'Agrupar y agregar CSV (tipo tabla dinámica)',
    description:
      'Agrupa las filas de un CSV por una columna y agrega una columna numérica: suma, promedio, conteo, mínimo, máximo o mediana. Opcionalmente filtra filas antes de agrupar. Equivale a una tabla dinámica rápida.',
    readOnly: true,
    inputSchema: {
      ruta: z.string().describe('Ruta del CSV en el directorio de datos.'),
      columna_grupo: z.string().describe('Columna por la que agrupar (ej. "vendedor", "mes", "categoria").'),
      columna_valor: z.string().optional().describe('Columna numérica a agregar. No es necesaria si la agregación es "conteo".'),
      agregacion: z.enum(['suma', 'promedio', 'conteo', 'minimo', 'maximo', 'mediana']).optional().describe('Agregación a aplicar (por defecto "suma").'),
      filtro_columna: z.string().optional().describe('Columna para filtrar filas antes de agrupar.'),
      filtro_valor: z.string().optional().describe('Valor exacto del filtro.'),
      orden: z.enum(['desc', 'asc']).optional().describe('Orden por valor agregado (por defecto desc).'),
      limite: z.number().int().min(1).max(1000).optional().describe('Máximo de grupos a devolver (por defecto 50).'),
      delimitador: z.string().optional().describe('Delimitador del CSV (autodetectado por defecto).'),
    },
    handler: async (args: any) => {
      const { file, tabla } = await cargarTabla(ctx, args.ruta, 100_000, args.delimitador);
      const { columna_grupo: grupo, agregacion = 'suma', columna_valor: valor } = args;
      if (!tabla.headers.includes(grupo)) {
        throw new Error(`La columna "${grupo}" no existe. Disponibles: ${tabla.headers.join(', ')}.`);
      }
      if (agregacion !== 'conteo' && !valor) {
        throw new Error(`La agregación "${agregacion}" requiere indicar columna_valor.`);
      }

      let filas = tabla.rows;
      if (args.filtro_columna) {
        if (args.filtro_valor === undefined) throw new Error('Debes indicar filtro_valor junto con filtro_columna.');
        const v = String(args.filtro_valor).toLowerCase();
        filas = filas.filter((r) => (r[args.filtro_columna] ?? '').toLowerCase() === v);
      }

      const buckets = new Map<string, number[]>();
      for (const r of filas) {
        const key = (r[grupo] ?? '(vacío)').trim() || '(vacío)';
        if (!buckets.has(key)) buckets.set(key, []);
        if (agregacion === 'conteo') {
          buckets.get(key)!.push(1);
        } else {
          const n = guessNumber(r[valor!] ?? '');
          if (n !== null) buckets.get(key)!.push(n);
        }
      }
      if (buckets.size === 0) throw new Error('No quedaron filas tras aplicar los filtros.');

      const agregar = (xs: number[]): number => {
        const r4 = (n: number) => Number(n.toFixed(4));
        switch (agregacion) {
          case 'suma': return r4(xs.reduce((a, b) => a + b, 0));
          case 'promedio': return r4(xs.reduce((a, b) => a + b, 0) / xs.length);
          case 'conteo': return xs.length;
          case 'minimo': return r4(Math.min(...xs));
          case 'maximo': return r4(Math.max(...xs));
          case 'mediana': {
            const s = [...xs].sort((a, b) => a - b);
            const m = Math.floor(s.length / 2);
            return r4(s.length % 2 === 0 ? (s[m - 1]! + s[m]!) / 2 : s[m]!);
          }
          default:
            throw new Error(`Agregación no soportada: ${agregacion}`);
        }
      };

      const resultados = [...buckets.entries()]
        .map(([clave, xs]) => ({ grupo: clave, valor: xs.length ? agregar(xs) : 0, registros: xs.length }))
        .sort((a, b) => (args.orden === 'asc' ? a.valor - b.valor : b.valor - a.valor));
      const total = Number(resultados.reduce((a, r) => a + r.valor, 0).toFixed(4));
      const limite = args.limite ?? 50;

      return {
        archivo: path.basename(file),
        agrupacion: `${agregacion}(${valor ?? '(conteo)'}) por ${grupo}`,
        grupos: resultados.length,
        resultados: resultados.slice(0, limite),
        total_agregado: agregacion === 'promedio' ? 'n/a (promedio)' : total,
      };
    },
  });

  push({
    module: 'analisis',
    name: 'dataset_exportar_json',
    title: 'Convertir CSV a JSON/CSV/Markdown',
    description:
      'Lee un CSV y lo devuelve convertido al formato pedido (JSON de registros, CSV en texto o tabla Markdown para reportes). Opcionalmente guarda el resultado en disco con el parámetro salida.',
    inputSchema: {
      ruta: z.string().describe('Ruta del CSV en el directorio de datos.'),
      formato: z.enum(['json', 'csv', 'markdown']).describe('Formato de salida.'),
      salida: z.string().optional().describe('Ruta de archivo donde guardar el resultado (relativa al directorio de datos). Si se omite, solo se devuelve el contenido.'),
      limite: z.number().int().min(1).max(100_000).optional().describe('Máximo de filas a convertir (por defecto todas).'),
      delimitador: z.string().optional().describe('Delimitador del CSV (autodetectado por defecto).'),
    },
    handler: async (args: { ruta: string; formato: 'json' | 'csv' | 'markdown'; salida?: string; limite?: number; delimitador?: string }) => {
      const { file, tabla, totalFilas } = await cargarTabla(ctx, args.ruta, args.limite ?? 100_000, args.delimitador);
      let contenido: string;
      if (args.formato === 'json') {
        contenido = JSON.stringify(tabla.rows, null, 2);
      } else if (args.formato === 'csv') {
        contenido = toCsv([tabla.headers, ...tabla.rows.map((r) => tabla.headers.map((h) => r[h] ?? ''))]);
      } else {
        contenido = toMarkdownTable([tabla.headers, ...tabla.rows.map((r) => tabla.headers.map((h) => r[h] ?? ''))]);
      }

      let guardado_en: string | null = null;
      if (args.salida) {
        const destino = resolveAllowedPath(ctx.config.dataDir, args.salida);
        await fs.mkdir(path.dirname(destino), { recursive: true });
        await fs.writeFile(destino, contenido, 'utf8');
        guardado_en = destino;
        log.info('dataset_exportado', { origen: file, destino, formato: args.formato });
      }
      return {
        origen: path.basename(file),
        filas_totales: totalFilas,
        filas_convertidas: tabla.rows.length,
        formato: args.formato,
        guardado_en,
        contenido: contenido.length > 30_000 ? `${contenido.slice(0, 30_000)}\n…(truncado, ${contenido.length} caracteres en total)` : contenido,
      };
    },
  });

  push({
    module: 'analisis',
    name: 'datos_estadisticas',
    title: 'Estadística descriptiva de valores',
    description:
      'Calcula estadísticas de un arreglo de números que tú proporciones: media, mediana, desviación, cuartiles, outliers, valor mínimo/máximo con su etiqueta, cambio porcentual entre primero y último, y tendencia con pronóstico. Útil para series cortas que el usuario pega en el chat.',
    readOnly: true,
    inputSchema: {
      valores: z.array(z.number()).min(1).describe('Serie de números a analizar.'),
      etiquetas: z.array(z.string()).optional().describe('Etiquetas opcionales (misma longitud), ej. meses o sucursales.'),
    },
    handler: async (args: { valores: number[]; etiquetas?: string[] }) => {
      if (args.etiquetas && args.etiquetas.length !== args.valores.length) {
        throw new Error(`Las etiquetas (${args.etiquetas.length}) deben tener la misma longitud que los valores (${args.valores.length}).`);
      }
      const r = resumir(args.valores);
      const et = args.etiquetas;
      const iMax = args.valores.indexOf(Math.max(...args.valores));
      const iMin = args.valores.indexOf(Math.min(...args.valores));
      return {
        ...r,
        maximo_etiqueta: et?.[iMax] ?? null,
        minimo_etiqueta: et?.[iMin] ?? null,
        cambio_primero_ultimo_pct: args.valores.length >= 2 && args.valores[0] !== 0 ? cambioPct(args.valores[args.valores.length - 1]!, args.valores[0]!) : null,
        tendencia: args.valores.length >= 2 ? regresionLineal(args.valores) : null,
      };
    },
  });

  push({
    module: 'analisis',
    name: 'datos_correlacion',
    title: 'Correlación entre dos series',
    description:
      'Calcula la correlación de Pearson entre dos series numéricas (misma longitud) y la interpreta (positiva/negativa, fuerte/moderada/débil). Sirve para saber si dos variables se mueven juntas: ventas vs. inversión en publicidad, etc.',
    readOnly: true,
    inputSchema: {
      valores_a: z.array(z.number()).min(2).describe('Primera serie numérica.'),
      valores_b: z.array(z.number()).min(2).describe('Segunda serie numérica (misma longitud).'),
      etiquetas: z.array(z.string()).optional().describe('Etiquetas de los periodos (opcional).'),
    },
    handler: async (args: { valores_a: number[]; valores_b: number[]; etiquetas?: string[] }) => {
      if (args.valores_a.length !== args.valores_b.length) {
        throw new Error(`Las series deben tener la misma longitud (a: ${args.valores_a.length}, b: ${args.valores_b.length}).`);
      }
      const r = pearson(args.valores_a, args.valores_b);
      const abs = Math.abs(r);
      const fuerza = abs >= 0.8 ? 'muy fuerte' : abs >= 0.6 ? 'fuerte' : abs >= 0.4 ? 'moderada' : abs >= 0.2 ? 'débil' : 'muy débil o nula';
      const direccion = r > 0 ? 'positiva' : r < 0 ? 'negativa' : 'nula';
      return {
        n: args.valores_a.length,
        pearson_r: Number(r.toFixed(4)),
        r_cuadrado: Number((r * r).toFixed(4)),
        interpretacion:
          `Correlación ${direccion} ${fuerza} (r=${r.toFixed(3)}).` +
          (abs >= 0.6 && args.valores_a.length < 10
            ? ' Precaución: muestra pequeña, la correlación puede ser inestable.'
            : ''),
      };
    },
  });

  push({
    module: 'analisis',
    name: 'datos_serie_tiempo',
    title: 'Analizar serie de tiempo',
    description:
      'Analiza una serie temporal: resumen estadístico, media móvil, tendencia lineal, pronóstico a N periodos, cambio porcentual total y CAGR (tasa compuesta) si indicas cuántos periodos por año. Ideal para ventas mensuales o tráfico web.',
    readOnly: true,
    inputSchema: {
      valores: z.array(z.number()).min(2).describe('Valores de la serie en orden cronológico.'),
      etiquetas: z.array(z.string()).optional().describe('Etiquetas de cada periodo (meses, años…).'),
      ventana_media_movil: z.number().int().min(2).max(50).optional().describe('Ventana de la media móvil (por defecto 3).'),
      periodos_pronostico: z.number().int().min(0).max(24).optional().describe('Cuántos periodos futuros pronosticar (por defecto 0).'),
      periodos_por_anio: z.number().int().min(1).max(366).optional().describe('Periodos por año para calcular CAGR, ej. 12 para meses.'),
    },
    handler: async (args: { valores: number[]; etiquetas?: string[]; ventana_media_movil?: number; periodos_pronostico?: number; periodos_por_anio?: number }) => {
      if (args.etiquetas && args.etiquetas.length !== args.valores.length) {
        throw new Error(`Las etiquetas (${args.etiquetas.length}) deben coincidir con los valores (${args.valores.length}).`);
      }
      const ventana = args.ventana_media_movil ?? 3;
      const reg = regresionLineal(args.valores);
      const n = args.valores.length;

      const pronosticos: { periodo: string; valor: number }[] = [];
      for (let k = 1; k <= (args.periodos_pronostico ?? 0); k++) {
        const idx = n + k - 1;
        pronosticos.push({
          periodo: args.etiquetas?.length ? `siguiente_${k}` : `periodo_${idx + 1}`,
          valor: Number((reg.pendiente * idx + reg.intercepto).toFixed(4)),
        });
      }

      const resultado: Record<string, unknown> = {
        n,
        resumen: resumir(args.valores),
        media_movil: { ventana, valores: mediaMovil(args.valores, ventana) },
        tendencia: reg,
        cambio_total_pct: args.valores[0] !== 0 ? cambioPct(args.valores[n - 1]!, args.valores[0]!) : null,
        pronosticos,
      };
      if (args.periodos_por_anio) {
        const anios = (n - 1) / args.periodos_por_anio;
        resultado.cagr_pct =
          args.valores[0]! > 0 && args.valores[n - 1]! > 0 && anios > 0
            ? cagr(args.valores[0]!, args.valores[n - 1]!, anios)
            : null;
      }
      return resultado;
    },
  });
}

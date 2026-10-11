import { z } from 'zod';
import type { ServerCtx } from '../context.js';
import type { ToolDef } from '../lib/result.js';
import { readTextFile, resolveAllowedPath } from '../lib/store.js';
import { PlantillasRepo } from '../db/plantillas.js';
import { renderPlantilla, extraerDatos, resumirTexto } from '../lib/text.js';
import path from 'node:path';
import { log } from '../logger.js';

export function documentosToolDefs(defs: ToolDef[], ctx: ServerCtx): void {
  const push = (d: ToolDef) => void defs.push(d);
  const plantillas = () => new PlantillasRepo(ctx.db);

  push({
    module: 'documentos',
    name: 'documento_leer',
    title: 'Leer documento o archivo',
    description:
      'Lee el contenido de un archivo de texto plano de tus datos: .md, .txt, .csv, .json, .log, .yml. Sirve para que revises un archivo y luego uses otras herramientas (resumir, extraer, analizar). Tiene un límite de 2 MB.',
    readOnly: true,
    inputSchema: {
      ruta: z.string().min(1).describe('Ruta del archivo. Puede ser relativa al directorio de datos (ej. "datasets/ventas.csv") o absoluta dentro de las áreas permitidas.'),
    },
    handler: async (args: { ruta: string }) => {
      const file = resolveAllowedPath(ctx.config.dataDir, args.ruta, [process.cwd()]);
      const contenido = await readTextFile(file);
      return {
        ruta: file,
        bytes: Buffer.byteLength(contenido, 'utf8'),
        lineas: contenido.split('\n').length,
        contenido,
      };
    },
  });

  push({
    module: 'documentos',
    name: 'documento_resumir',
    title: 'Resumir y analizar un texto',
    description:
      'Devuelve métricas y estructura de un texto: número de palabras, oraciones, párrafos, tiempo de lectura, nivel de legibilidad (fórmula Fernández-Huerta para español) y las palabras más frecuentes (excluyendo palabras vacías). Úsala para entender o evaluar un texto antes de editarlo.',
    readOnly: true,
    inputSchema: {
      texto: z.string().min(10).describe('El texto completo a analizar.'),
      top_palabras: z.number().int().min(1).max(50).optional().describe('Cuántas palabras clave devolver (por defecto 10).'),
    },
    handler: async (args: { texto: string; top_palabras?: number }) => resumirTexto(args.texto, args.top_palabras ?? 10),
  });

  push({
    module: 'documentos',
    name: 'documento_extraer_datos',
    title: 'Extraer datos de un texto (NER con regex)',
    description:
      'Extrae y valida datos de contacto y fiscales de un texto libre: correos, teléfonos, URLs, RFC, CURP, montos, fechas y códigos postales. Ideal para procesar correos de clientes, leads o facturas pegadas como texto.',
    readOnly: true,
    inputSchema: {
      texto: z.string().min(5).describe('Texto de donde extraer los datos (correo, firma, contrato, nota).'),
    },
    handler: async (args: { texto: string }) => {
      const datos = extraerDatos(args.texto);
      const totales = Object.fromEntries(Object.entries(datos).map(([k, v]) => [k, v.length]));
      return { totales_encontrados: totales, datos };
    },
  });

  push({
    module: 'documentos',
    name: 'plantilla_renderizar',
    title: 'Renderizar plantilla',
    description:
      'Toma una plantilla guardada (con marcadores tipo {{cliente}}, {{total}}) y sustituye las variables para generar el documento final: contratos, propuestas, correos, recordatorios. Indica qué variables faltaron si falta información.',
    inputSchema: {
      nombre: z.string().describe('Nombre de la plantilla guardada.'),
      variables: z.record(z.union([z.string(), z.number()])).describe('Objeto JSON con los valores: {"cliente": "ACME SA de CV", "total": 11600}.'),
    },
    handler: async (args: { nombre: string; variables: Record<string, string | number> }) => {
      const repo = plantillas();
      const p = repo.obtener(args.nombre);
      if (!p) {
        const disponibles = repo.listar().map((x) => x.nombre).join(', ') || '(ninguna)';
        throw new Error(`No existe la plantilla "${args.nombre}". Disponibles: ${disponibles}.`);
      }
      const r = renderPlantilla(p.contenido, args.variables);
      return {
        nombre: p.nombre,
        advertencia: r.variablesFaltantes.length
          ? `Faltaron valores para: ${r.variablesFaltantes.join(', ')}. Se dejaron los marcadores visibles.`
          : 'Todas las variables se sustituyeron correctamente.',
        variables_usadas: r.variablesUsadas,
        variables_faltantes: r.variablesFaltantes,
        documento: r.texto,
      };
    },
  });

  push({
    module: 'documentos',
    name: 'plantilla_guardar',
    title: 'Guardar plantilla',
    description:
      'Guarda o actualiza una plantilla de documento con marcadores {{variable}}. Detecta automáticamente las variables declaradas. Si el nombre ya existe, la versión anterior se reemplaza.',
    destructive: true,
    idempotent: true,
    inputSchema: {
      nombre: z.string().min(3).describe('Nombre único de la plantilla, ej. "recordatorio_pago".'),
      contenido: z.string().min(10).describe('Cuerpo de la plantilla con marcadores {{variable}}.'),
      descripcion: z.string().optional().describe('Para qué sirve la plantilla.'),
    },
    handler: async (args: { nombre: string; contenido: string; descripcion?: string }) => {
      const resultado = plantillas().guardar(args);
      log.info('plantilla_guardada', { nombre: args.nombre, variables: resultado.variables.length });
      return { nombre: args.nombre, variables_detectadas: resultado.variables, reemplazada: resultado.reemplazada, total: resultado.total };
    },
  });

  push({
    module: 'documentos',
    name: 'plantilla_listar',
    title: 'Listar plantillas',
    description: 'Lista las plantillas guardadas con su descripción y las variables que requieren. Úsala antes de renderizar para saber qué variables necesitas pedir.',
    readOnly: true,
    inputSchema: {
      query: z.string().optional().describe('Filtro parcial por nombre o descripción.'),
    },
    handler: async (args: { query?: string }) => {
      const lista = plantillas().listar(args.query);
      return { total: lista.length, plantillas: lista.map(({ contenido, ...resto }) => ({ ...resto, caracteres: contenido.length })) };
    },
  });
}

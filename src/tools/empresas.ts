/**
 * Módulo «empresas» (fase 5A): espacios por negocio.
 *
 * El caso real: un despacho contable (o un dueño con dos giros) usa UN servidor
 * para varias empresas. Cada empresa tiene su inventario, sus pedidos y sus
 * plantillas; nada se mezcla. Con `empresa_actual` se cambia de espacio, y las
 * claves de API se pueden atar a una empresa para que un cliente solo vea lo suyo.
 */
import { z } from 'zod';
import type { ServerCtx } from '../context.js';
import type { ToolDef } from '../lib/result.js';
import { log } from '../logger.js';
import {
  listarEmpresas,
  obtenerEmpresa,
  crearEmpresa,
  editarEmpresa,
  establecerEmpresaActiva,
  empresaActiva,
  empresaActivaDetalle,
  resumenEmpresa,
  compararEmpresas,
} from '../lib/empresas.js';

function resumen(e: ReturnType<typeof listarEmpresas>[number]) {
  const { id, nombre, rfc, regimen, cp, series, color, archivada, creada_en } = e;
  return { id, nombre, rfc, regimen, cp, series, color, archivada, creada_en };
}

export function empresasToolDefs(defs: ToolDef[], ctx: ServerCtx): void {
  const push = (d: ToolDef) => void defs.push(d);

  push({
    module: 'empresas',
    name: 'empresas_listar',
    title: 'Listar empresas',
    description:
      'Lista los espacios de trabajo (empresas) con sus datos fiscales y cuál está activa para esta sesión. Útil para saber en qué negocio estás operando antes de tocar inventario, pedidos o facturas.',
    readOnly: true,
    inputSchema: {
      incluir_archivadas: z.boolean().optional().describe('Si es true, incluye también las empresas archivadas.'),
    },
    handler: async (args: { incluir_archivadas?: boolean }) => {
      const activa = empresaActivaDetalle(ctx);
      const empresas = listarEmpresas(ctx, { incluirArchivadas: args.incluir_archivadas });
      return {
        activa: { id: activa.id, nombre: activa.nombre, atada_a_la_clave: Boolean(ctx.apiKey?.empresaId) },
        total: empresas.length,
        empresas: empresas.map(resumen),
        para_cambiar: 'Usa `empresa_actual` con el id o el nombre.',
      };
    },
  });

  push({
    module: 'empresas',
    name: 'empresa_crear',
    title: 'Crear empresa',
    description:
      'Crea un espacio nuevo (inventario, pedidos y plantillas propios, totalmente aislados de las demás). Si le pones RFC/régimen/CP, sus facturas CFDI salen con esos datos de emisor.',
    inputSchema: {
      nombre: z.string().min(2).describe('Nombre de la empresa, ej. "Ferretería El Tornillo".'),
      rfc: z.string().optional().describe('RFC del emisor (12 o 13 caracteres). Opcional al crear; se puede completar después.'),
      regimen: z.string().optional().describe('Clave del régimen fiscal, ej. "601", "612", "626".'),
      cp: z.string().optional().describe('Código postal del domicilio fiscal (5 dígitos).'),
      series: z.string().optional().describe('Serie de folios para sus comprobantes, ej. "A", "FERR". Por defecto "A".'),
      color: z.string().optional().describe('Color para identificarla en reportes/panel, ej. "#4ec3e0".'),
    },
    handler: async (args: { nombre: string; rfc?: string; regimen?: string; cp?: string; series?: string; color?: string }) => {
      const empresa = crearEmpresa(ctx, args);
      log.info('empresa_creada', { id: empresa.id, nombre: empresa.nombre });
      return {
        ...resumen(empresa),
        mensaje: `Empresa «${empresa.nombre}» creada con su propio espacio. Sus datos no se mezclan con los de otras empresas.`,
        activarla: `empresa_actual { "empresa": "${empresa.id}" }`,
      };
    },
  });

  push({
    module: 'empresas',
    name: 'empresa_actual',
    title: 'Ver o cambiar la empresa activa',
    description:
      'Consulta cuál es la empresa activa o cambia a otra. Todo lo que hagas después (inventario, pedidos, plantillas, facturas) ocurre en esa empresa. Una clave de API atada a una empresa no puede cambiar de espacio.',
    inputSchema: {
      empresa: z.string().optional().describe('Id (EMP-xxxxxx) o nombre de la empresa a la que quieres cambiar. Si lo omites, solo te dice cuál está activa.'),
    },
    handler: async (args: { empresa?: string }) => {
      if (!args.empresa) {
        const activa = empresaActivaDetalle(ctx);
        return {
          activa: resumen(activa),
          atada_a_la_clave: Boolean(ctx.apiKey?.empresaId),
          ...(ctx.apiKey?.empresaId
            ? { nota: 'Esta clave está atada a esa empresa: siempre verás sus datos, sin importar el parámetro.' }
            : { como_cambiar: 'Pasa el id o el nombre en el parámetro "empresa".' }),
        };
      }
      const empresa = establecerEmpresaActiva(ctx, args.empresa);
      return {
        activa: resumen(empresa),
        mensaje: `La empresa activa es ahora «${empresa.nombre}». Inventario, pedidos y plantillas que consultes serán los suyos.`,
      };
    },
  });

  push({
    module: 'empresas',
    name: 'empresa_editar',
    title: 'Editar empresa',
    description:
      'Corrige nombre, RFC, régimen, código postal, serie de folios o color; o archiva una empresa para ocultarla sin perder datos (no se puede archivar la que está activa).',
    destructive: true,
    inputSchema: {
      empresa: z.string().describe('Id o nombre de la empresa.'),
      nombre: z.string().optional().describe('Nuevo nombre.'),
      rfc: z.string().optional().describe('Nuevo RFC.'),
      regimen: z.string().optional().describe('Nuevo régimen fiscal, ej. "612".'),
      cp: z.string().optional().describe('Nuevo código postal.'),
      series: z.string().optional().describe('Nueva serie de folios.'),
      color: z.string().optional().describe('Nuevo color.'),
      archivar: z.boolean().optional().describe('true = archivar (queda oculta, sus datos se conservan), false = reactivar.'),
    },
    handler: async (args: {
      empresa: string; nombre?: string; rfc?: string; regimen?: string; cp?: string; series?: string; color?: string; archivar?: boolean;
    }) => {
      const { empresa, ...cambios } = args;
      const actualizada = editarEmpresa(ctx, empresa, cambios);
      return {
        ...resumen(actualizada),
        mensaje: actualizada.archivada
          ? `«${actualizada.nombre}» quedó archivada: deja de aparecer, pero sus datos siguen aquí.`
          : `Empresa «${actualizada.nombre}» actualizada.`,
      };
    },
  });

  push({
    module: 'empresas',
    name: 'empresa_resumen',
    title: 'Resumen de una empresa',
    description:
      'Foto ejecutiva de una empresa: productos, valor del inventario (a precio y a costo), artículos bajo mínimo, pedidos por estado, ventas, ticket promedio y plantillas. Por defecto, la empresa activa.',
    readOnly: true,
    inputSchema: {
      empresa: z.string().optional().describe('Id o nombre de la empresa. Si lo omites, usa la activa.'),
    },
    handler: async (args: { empresa?: string }) => {
      const id = args.empresa ?? empresaActiva(ctx);
      if (args.empresa && ctx.apiKey?.empresaId && ctx.apiKey.empresaId !== obtenerEmpresa(ctx, args.empresa)?.id) {
        throw new Error('Esta clave está atada a otra empresa: pide una clave global o sin atar para verla.');
      }
      return resumenEmpresa(ctx, id);
    },
  });

  push({
    module: 'empresas',
    name: 'empresas_comparar',
    title: 'Comparar empresas',
    description:
      'Compara varias empresas lado a lado (ventas, pedidos, inventario, riesgos de stock). El reporte que un despacho necesita para ver todos sus clientes de un vistazo, sin mezclar cifras.',
    readOnly: true,
    inputSchema: {
      empresas: z.array(z.string()).optional().describe('Ids o nombres a comparar. Si lo omites, compara todas las activas.'),
    },
    handler: async (args: { empresas?: string[] }) => {
      if (ctx.apiKey?.empresaId) {
        throw new Error(
          `Esta clave ve una sola empresa (${ctx.apiKey.empresaId}). Para comparar varias, usa una clave global (sin --empresa) o el servidor local.`,
        );
      }
      return compararEmpresas(ctx, args.empresas);
    },
  });
}

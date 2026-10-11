import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { ZodRawShape } from 'zod';
import type { ServerCtx, IdentidadCliente } from '../context.js';
import { log } from '../logger.js';
import { resumirArgs } from './audit.js';
import { politicaDesdeScopes } from '../auth/politica.js';

export interface ToolInfo {
  name: string;
  title: string;
  description: string;
  module: string;
  readOnly: boolean;
}

export interface ToolDef {
  name: string;
  title: string;
  description: string;
  module: string;
  inputSchema: ZodRawShape;
  readOnly?: boolean;
  destructive?: boolean;
  idempotent?: boolean;
  handler: (args: any) => unknown | Promise<unknown>;
}

/** Metadatos de ejecución compartidos por todas las herramientas registradas. */
export interface ToolMeta {
  ctx: ServerCtx;
}

/** Respuesta MCP estándar en texto (JSON con formato legible para el modelo). */
export function json(data: unknown, resumen?: string) {
  const cuerpo = typeof data === 'string' ? data : JSON.stringify(data, null, 2);
  return {
    content: [{ type: 'text' as const, text: resumen ? `${resumen}\n\n${cuerpo}` : cuerpo }],
  };
}

export function aInfo(def: ToolDef): ToolInfo {
  return {
    name: def.name,
    title: def.title,
    description: def.description,
    module: def.module,
    readOnly: def.readOnly ?? false,
  };
}

const errorRespuesta = (texto: string) => ({
  isError: true,
  content: [{ type: 'text' as const, text: texto }],
});

/**
 * Envuelve el handler de una herramienta con las capas transversales:
 * límite de tasa → ejecución medida → métricas → auditoría (solo destructivas).
 */
function conGuardas(def: ToolDef, meta?: ToolMeta) {
  return async (args: any) => {
    const t0 = performance.now();
    const etiquetasBase = { herramienta: def.name, modulo: def.module };
    const { ctx } = meta ?? {};

    // 1. Límite de tasa por cliente autenticado (o "anon"/clave bootstrap).
    if (ctx?.limiter) {
      const claveCubeta = ctx.apiKey?.id ?? 'anon';
      const limite = ctx.limiter.consumir(claveCubeta);
      if (!limite.permitido) {
        ctx.metrics.inc('mcp_suite_rate_limit_bloqueos_total', { cliente: ctx.apiKey?.nombre ?? 'anon' });
        await ctx.audit.registrar({
          tipo: 'rate_limit',
          cliente: ctx.apiKey?.nombre ?? 'anon',
          herramienta: def.name,
          modulo: def.module,
          detalle: `límite ${limite.limite} req; reintentar en ${limite.retryAfterS}s`,
        });
        return errorRespuesta(
          `Límite de peticiones excedido (${limite.limite} por minuto por cliente). Reintenta en ${limite.retryAfterS} segundos.`,
        );
      }
    }

    try {
      const data = await def.handler(args ?? {});
      const ms = performance.now() - t0;
      log.debug('tool_ok', { tool: def.name, ms: Math.round(ms) });
      ctx?.metrics.inc('mcp_suite_tools_llamadas_total', { ...etiquetasBase, resultado: 'ok' });
      ctx?.metrics.observe('mcp_suite_tool_duracion_segundos', ms / 1000, etiquetasBase);
      if (ctx && !def.readOnly) {
        await ctx.audit.registrar({
          tipo: 'tool_destructiva',
          cliente: ctx.apiKey?.nombre ?? 'anon',
          herramienta: def.name,
          modulo: def.module,
          ok: true,
          duracion_ms: Math.round(ms),
          args_resumen: resumirArgs(args),
        });
      }
      return json(data);
    } catch (err: any) {
      const ms = performance.now() - t0;
      log.warn('tool_error', { tool: def.name, ms: Math.round(ms), error: err?.message ?? String(err) });
      ctx?.metrics.inc('mcp_suite_tools_llamadas_total', { ...etiquetasBase, resultado: 'error' });
      ctx?.metrics.observe('mcp_suite_tool_duracion_segundos', ms / 1000, etiquetasBase);
      if (ctx && !def.readOnly) {
        await ctx.audit.registrar({
          tipo: 'tool_destructiva',
          cliente: ctx.apiKey?.nombre ?? 'anon',
          herramienta: def.name,
          modulo: def.module,
          ok: false,
          duracion_ms: Math.round(ms),
          error: err?.message ?? String(err),
          args_resumen: resumirArgs(args),
        });
      }
      return errorRespuesta(`Error en "${def.name}": ${err?.message ?? String(err)}`);
    }
  };
}

/** Registra una herramienta concreta en una instancia MCP. */
export function registerTool(server: McpServer, catalog: ToolInfo[], def: ToolDef, meta?: ToolMeta): void {
  catalog.push(aInfo(def));
  server.registerTool(
    def.name,
    {
      title: def.title,
      description: def.description,
      inputSchema: def.inputSchema,
      annotations: {
        readOnlyHint: def.readOnly ?? false,
        destructiveHint: def.destructive ?? false,
        idempotentHint: def.idempotent ?? false,
        openWorldHint: def.module === 'devops',
      },
    },
    conGuardas(def, meta),
  );
}

/**
 * Registra un módulo completo aplicando la política de permisos del cliente.
 * Si la petición viene autenticada, solo se exponen las herramientas permitidas
 * por sus scopes; sin identidad (stdio local, AUTH_MODE=off) se exponen todas.
 * Devuelve cuántas quedaron fuera por permisos.
 */
export function registerModuloTools(
  server: McpServer,
  catalog: ToolInfo[],
  defs: ToolDef[],
  meta: ToolMeta,
  identidad?: IdentidadCliente,
): { registradas: number; omitidasPorPermisos: number } {
  const permitido = identidad ? politicaDesdeScopes(identidad.scopes) : () => true;
  let omitidas = 0;
  for (const def of defs) {
    if (!permitido({ name: def.name, module: def.module, readOnly: def.readOnly ?? false })) {
      omitidas++;
      continue;
    }
    registerTool(server, catalog, def, meta);
  }
  return { registradas: defs.length - omitidas, omitidasPorPermisos: omitidas };
}
